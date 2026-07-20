package org.catalogueoflife.editor.discussion;

import java.util.List;
import java.util.Locale;
import org.catalogueoflife.editor.discussion.dto.CreateDiscussionRequest;
import org.catalogueoflife.editor.discussion.dto.DiscussionPage;
import org.catalogueoflife.editor.discussion.dto.DiscussionResponse;
import org.catalogueoflife.editor.discussion.dto.UpdateDiscussionRequest;
import org.catalogueoflife.editor.name.IdSeqMapper;
import org.catalogueoflife.editor.name.Pagination;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DiscussionService {

  private static final String ENTITY = "discussion";

  private final DiscussionMapper discussions;
  private final IdSeqMapper idSeq;
  private final ProjectService projects;
  private final AppUserMapper users;
  private final DiscussionMentionService mentions;
  private final DiscussionLinkService links;

  public DiscussionService(DiscussionMapper discussions, IdSeqMapper idSeq, ProjectService projects,
      AppUserMapper users, DiscussionMentionService mentions, DiscussionLinkService links) {
    this.discussions = discussions;
    this.idSeq = idSeq;
    this.projects = projects;
    this.users = users;
    this.mentions = mentions;
    this.links = links;
  }

  // Any member may list/read. q = full-text over title+body; status/authorId are optional filters;
  // sort ∈ {created, modified}; order ∈ {asc, desc}. status/sort/order are validated + normalized
  // here so the mapper only ever sees safe values (order is a ${} literal in the SQL).
  public DiscussionPage search(int userId, int projectId, String q, String status, Integer authorId,
      String sort, String order, int limit, int offset) {
    projects.requireRole(userId, projectId);
    String normQ = (q == null || q.isBlank()) ? null : q.trim();
    String normStatus = normalizeStatus(status);
    String normSort = "modified".equalsIgnoreCase(sort) ? "modified" : "created";
    String normOrder = "asc".equalsIgnoreCase(order) ? "ASC" : "DESC";
    List<Discussion> items = discussions.search(projectId, normQ, normStatus, authorId, normSort,
        normOrder, Pagination.clampLimit(limit), Pagination.clampOffset(offset));
    long total = discussions.count(projectId, normQ, normStatus, authorId);
    return new DiscussionPage(items.stream().map(DiscussionResponse::of).toList(), total);
  }

  public Discussion get(int userId, int projectId, int id) {
    projects.requireRole(userId, projectId);
    return requireInProject(projectId, id);
  }

  // Detail view: the discussion plus its resolved #nameID / @orcid mentions.
  public DiscussionResponse getDetail(int userId, int projectId, int id) {
    projects.requireRole(userId, projectId);
    Discussion d = requireInProject(projectId, id);
    return DiscussionResponse.of(d, mentions.resolve(projectId, d.getBody()));
  }

  @Transactional
  public Discussion create(int userId, int projectId, CreateDiscussionRequest req) {
    projects.requireRole(userId, projectId); // any member may start a discussion
    AppUser author = users.findById(userId);
    Discussion d = new Discussion();
    d.setProjectId(projectId);
    d.setId(idSeq.allocate(projectId, ENTITY));
    d.setTitle(req.title());
    d.setBody(req.body());
    d.setStatus(DiscussionStatus.OPEN.name());
    d.setVisibility(DiscussionVisibility.INTERNAL.name());
    d.setAuthorId(userId);
    d.setAuthorOrcid(author == null ? null : author.getOrcid());
    d.setCreatedVia("UI");
    discussions.insert(d);
    links.reconcile(projectId, d.getId()); // #nameID refs in the body -> reverse-links
    // re-read to pick up the DB defaults (created_at/updated_at, version) + the joined authorName
    return requireInProject(projectId, d.getId());
  }

  // Token-gated external submission (no session user): arrives as REVIEW/API for editor triage.
  // Access control is the API token (see DiscussionApiTokenService), so there is no role check here.
  @Transactional
  public Discussion createExternal(int projectId, String title, String body, String authorOrcid) {
    Discussion d = new Discussion();
    d.setProjectId(projectId);
    d.setId(idSeq.allocate(projectId, ENTITY));
    d.setTitle(title);
    d.setBody(body);
    d.setStatus(DiscussionStatus.REVIEW.name());
    d.setVisibility(DiscussionVisibility.INTERNAL.name());
    d.setAuthorId(null);
    d.setAuthorOrcid(authorOrcid);
    d.setCreatedVia("API");
    discussions.insert(d);
    links.reconcile(projectId, d.getId());
    return requireInProject(projectId, d.getId());
  }

  @Transactional
  public Discussion update(int userId, int projectId, int id, UpdateDiscussionRequest req) {
    String role = projects.requireRole(userId, projectId);
    Discussion existing = requireInProject(projectId, id);
    requireAuthorOrEditor(userId, role, existing);
    int updated = discussions.update(projectId, id, req.title(), req.body(), req.version());
    if (updated == 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "conflict: stale version");
    }
    links.reconcile(projectId, id); // body may have gained/lost #nameID refs
    return requireInProject(projectId, id);
  }

  @Transactional
  public Discussion setStatus(int userId, int projectId, int id, String statusRaw) {
    String role = projects.requireRole(userId, projectId);
    Discussion existing = requireInProject(projectId, id);
    requireAuthorOrEditor(userId, role, existing);
    String status = normalizeStatus(statusRaw);
    if (status == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status is required");
    }
    discussions.updateStatus(projectId, id, status);
    return requireInProject(projectId, id);
  }

  @Transactional
  public void delete(int userId, int projectId, int id) {
    String role = projects.requireRole(userId, projectId);
    if (!isEditor(role)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "editor required");
    }
    requireInProject(projectId, id);
    discussions.delete(projectId, id);
  }

  @Transactional
  public Discussion setVisibility(int userId, int projectId, int id, String visRaw) {
    String role = projects.requireRole(userId, projectId);
    if (!isEditor(role)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "editor required to change visibility");
    }
    requireInProject(projectId, id);
    discussions.updateVisibility(projectId, id, normalizeVisibility(visRaw));
    return requireInProject(projectId, id);
  }

  // -- public (unauthenticated) reads: only PUBLIC discussions are ever returned --------------------

  public List<DiscussionResponse> publicList(int projectId) {
    return discussions.findPublicByProject(projectId).stream().map(DiscussionResponse::of).toList();
  }

  public DiscussionResponse publicDetail(int projectId, int id) {
    Discussion d = requirePublic(projectId, id);
    return DiscussionResponse.of(d, mentions.resolve(projectId, d.getBody()));
  }

  // The discussion iff it is PUBLIC, else 404 -- gates the public comments endpoint too.
  public Discussion requirePublic(int projectId, int id) {
    Discussion d = discussions.findPublicByIdInProject(projectId, id);
    if (d == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "discussion not found");
    }
    return d;
  }

  private static String normalizeVisibility(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "visibility is required");
    }
    try {
      return DiscussionVisibility.valueOf(raw.trim().toUpperCase(Locale.ROOT)).name();
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown visibility: " + raw);
    }
  }

  private Discussion requireInProject(int projectId, int id) {
    Discussion d = discussions.findByIdInProject(projectId, id);
    if (d == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "discussion not found");
    }
    return d;
  }

  // A discussion's own author may edit/close it; otherwise editor (or owner) role is required.
  private void requireAuthorOrEditor(int userId, String role, Discussion d) {
    boolean isAuthor = d.getAuthorId() != null && d.getAuthorId() == userId;
    if (!isAuthor && !isEditor(role)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "author or editor required");
    }
  }

  private static boolean isEditor(String role) {
    return Role.OWNER.dbValue().equals(role) || Role.EDITOR.dbValue().equals(role);
  }

  // Blank -> null (no filter); otherwise resolve against DiscussionStatus, 400 on anything unknown.
  private static String normalizeStatus(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return DiscussionStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT)).name();
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown status: " + raw);
    }
  }
}
