package org.catalogueoflife.editor.lock;

import java.util.List;
import org.catalogueoflife.editor.discussion.Discussion;
import org.catalogueoflife.editor.discussion.DiscussionMapper;
import org.catalogueoflife.editor.discussion.DiscussionStatus;
import org.catalogueoflife.editor.lock.dto.AcquireLockRequest;
import org.catalogueoflife.editor.lock.dto.LockResponse;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.validation.SubtreeValidationEvent;
import org.catalogueoflife.editor.validation.ValidationEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// Advisory, auto-expiring soft locks: acquiring/holding one never blocks another user's write
// (the optimistic `version` check on the domain entity remains the real safety net, per spec
// §5.9) -- this is purely a "someone is working here" signal. Any project member may hold a
// lock: reading & signalling intent is not a write, so unlike Reference/NameUsage/Tree writes
// there is no owner/editor gate here, only the any-member membership check.
@Service
public class LockService {

  static final int MIN_TTL_SECONDS = 30;
  static final int MAX_TTL_SECONDS = 3600;

  private final LockMapper locks;
  private final ProjectService projects;
  private final DiscussionMapper discussions;
  private final ApplicationEventPublisher events;
  private final int defaultTtlSeconds;

  public LockService(LockMapper locks, ProjectService projects, DiscussionMapper discussions,
      ApplicationEventPublisher events,
      @Value("${coldp.lock.ttl-seconds:300}") int defaultTtlSeconds) {
    this.locks = locks;
    this.projects = projects;
    this.discussions = discussions;
    this.events = events;
    this.defaultTtlSeconds = defaultTtlSeconds;
  }

  @Transactional
  public LockResponse acquire(int actorId, int projectId, AcquireLockRequest req) {
    projects.requireRole(actorId, projectId);
    int ttl = clampTtl(req.ttlSeconds());
    Integer discussionId = validateObjective(projectId, req.discussionId());
    locks.upsertTakeover(projectId, req.entityType(), req.entityId(), actorId, discussionId, ttl);
    // Read back the row rather than trusting the UPSERT's own affected-row count: this is the
    // single source of truth for who ended up holding it, whether that's us (fresh
    // acquire/takeover) or the still-active other user the UPSERT's WHERE clause left untouched.
    Lock current = locks.findByEntity(projectId, req.entityType(), req.entityId());
    return toResponse(current, actorId);
  }

  // The objective is optional intent: null passes through untouched (an ungrouped/plain lock). A
  // present-but-invalid reference (not an OPEN discussion in this project) is a client error -> 400,
  // mirroring CurrentObjective's X-Objective-Id validation for changelog attribution -- surfacing a
  // stale selection rather than silently dropping the declared intent.
  private Integer validateObjective(int projectId, Integer discussionId) {
    if (discussionId == null) {
      return null;
    }
    Discussion d = discussions.findByIdInProject(projectId, discussionId);
    if (d == null || !DiscussionStatus.OPEN.name().equals(d.getStatus())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "unknown or non-open objective (discussion): " + discussionId);
    }
    return discussionId;
  }

  @Transactional
  public LockResponse refresh(int actorId, int projectId, int lockId, Integer ttlSeconds) {
    projects.requireRole(actorId, projectId);
    int ttl = clampTtl(ttlSeconds);
    int rows = locks.refresh(projectId, lockId, actorId, ttl);
    if (rows == 0) {
      Lock existing = locks.findById(projectId, lockId);
      if (existing == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "lock not found");
      }
      // Row exists but the UPDATE's WHERE (user_id = actor AND expires_at > now()) didn't match:
      // either someone else now holds it (took it over after it expired) or it expired under us.
      // Either way the actor no longer holds it -- a conflict against their expectation.
      throw new ResponseStatusException(HttpStatus.CONFLICT, "lock not held by you");
    }
    return toResponse(locks.findById(projectId, lockId), actorId);
  }

  @Transactional
  public void release(int actorId, int projectId, int lockId) {
    projects.requireRole(actorId, projectId);
    // Load before deleting: delete() keys on lockId only, but a subtree revalidate needs the lock's
    // entity + objective. Reading it first (rather than trusting the caller) also keeps the
    // "released a group" signal honest -- we only fire for a lock this actor actually held and removed.
    Lock lock = locks.findById(projectId, lockId);
    int rows = locks.delete(projectId, lockId, actorId);
    if (rows == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "lock not found");
    }
    // Only objective-tagged taxon locks trigger a subtree recompute -- deliberate group-work, not an
    // ad-hoc single-field lock (those are already covered by the per-usage auto-trigger). Published
    // inside the transaction; ValidationTrigger picks it up AFTER_COMMIT on the async pool.
    if (lock != null && ValidationEvent.ENTITY_NAME_USAGE.equals(lock.getEntityType())
        && lock.getDiscussionId() != null && lock.getEntityId() != null) {
      events.publishEvent(new SubtreeValidationEvent(projectId, lock.getEntityId()));
    }
  }

  public List<LockResponse> listActive(int actorId, int projectId) {
    projects.requireRole(actorId, projectId);
    return locks.findActive(projectId).stream().map(l -> toResponse(l, actorId)).toList();
  }

  private static LockResponse toResponse(Lock l, int actorId) {
    return new LockResponse(l.getId(), l.getEntityType(), l.getEntityId(), l.getUserId(),
        l.getUsername(), l.getAcquiredAt(), l.getExpiresAt(), l.getUserId() == actorId,
        l.getDiscussionId(), l.getDiscussionTitle());
  }

  private int clampTtl(Integer ttlSeconds) {
    int ttl = ttlSeconds == null ? defaultTtlSeconds : ttlSeconds;
    return Math.max(MIN_TTL_SECONDS, Math.min(MAX_TTL_SECONDS, ttl));
  }
}
