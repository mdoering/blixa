package org.catalogueoflife.editor.discussion;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;
import org.springframework.web.server.ResponseStatusException;

// Resolves the optional X-Objective-Id request header into a validated discussion id for the
// current HTTP request. The "work objective" a change/lock is made under is simply an OPEN
// discussion (the task entity was retired -- see
// docs/superpowers/specs/2026-07-24-work-objective-as-discussion-design.md).
//
// Request-scoped (one instance per request, backed by a CGLIB proxy injected into singletons like
// AuditService) so the header is read once and the discussion lookup memoized for the request:
// AuditService.record calls resolve(projectId) on every audited write, and a request can contain
// several writes.
//
// Objectives are optional/soft: an absent or blank header resolves to null (an ungrouped change),
// but a *present* header that doesn't parse, or doesn't name an OPEN discussion in this project, is
// a client error -> 400, surfacing a stale selection rather than silently dropping the grouping.
// Since AuditService.record runs inside the caller's write transaction, a 400 thrown here rolls the
// whole write back.
@Component
@RequestScope
public class CurrentObjective {

  public static final String HEADER = "X-Objective-Id";

  private final HttpServletRequest request;
  private final DiscussionMapper discussions;

  private Integer resolvedProjectId;
  private Integer discussionId;

  public CurrentObjective(HttpServletRequest request, DiscussionMapper discussions) {
    this.request = request;
    this.discussions = discussions;
  }

  public Integer resolve(int projectId) {
    if (resolvedProjectId != null && resolvedProjectId == projectId) {
      return discussionId;
    }
    resolvedProjectId = projectId;
    discussionId = resolveHeader(projectId);
    return discussionId;
  }

  private Integer resolveHeader(int projectId) {
    String header = request.getHeader(HEADER);
    if (header == null || header.isBlank()) {
      return null;
    }
    int id;
    try {
      id = Integer.parseInt(header.trim());
    } catch (NumberFormatException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "invalid " + HEADER + " header: " + header);
    }
    Discussion d = discussions.findByIdInProject(projectId, id);
    if (d == null || !DiscussionStatus.OPEN.name().equals(d.getStatus())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "unknown or non-open objective (discussion): " + id);
    }
    return id;
  }
}
