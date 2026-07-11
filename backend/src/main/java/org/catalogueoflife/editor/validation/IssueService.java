package org.catalogueoflife.editor.validation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.catalogueoflife.editor.name.Pagination;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.validation.dto.IssueResponse;
import org.catalogueoflife.editor.validation.dto.IssueSummaryResponse;
import org.catalogueoflife.editor.validation.dto.ReviewRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// The issue API's authz + orchestration layer (Task 2 of the validation-engine plan). Reads
// (list/summary) are open to any project member, mirroring TaskService/ChangeController's
// "requireRole -> 404 non-member, any role may read" convention; reviewing an issue additionally
// requires being owner/editor (a viewer may see problems but not triage them -> 403); an
// on-demand full revalidate is a heavier write-adjacent action gated to owner/editor, same tier as
// ProjectService.updateMetadata.
@Service
public class IssueService {

  private final IssueMapper issues;
  private final ProjectService projects;
  private final ValidationService validationService;

  public IssueService(IssueMapper issues, ProjectService projects, ValidationService validationService) {
    this.issues = issues;
    this.projects = projects;
    this.validationService = validationService;
  }

  public List<IssueResponse> list(int actorId, int projectId, String status, String severity,
      String entityType, Integer entityId, int limit, int offset) {
    projects.requireRole(actorId, projectId); // any member may read -- 404 if not a member
    String statusFilter = normalizeStatusFilter(status);
    String severityFilter = normalizeSeverityFilter(severity);
    String entityTypeFilter = normalizeEntityTypeFilter(entityType);
    int clampedLimit = Pagination.clampLimit(limit);
    int clampedOffset = Pagination.clampOffset(offset);
    return issues.findByProject(projectId, statusFilter, severityFilter, entityTypeFilter, entityId,
        clampedLimit, clampedOffset);
  }

  public IssueSummaryResponse summary(int actorId, int projectId) {
    projects.requireRole(actorId, projectId); // any member may read
    return buildSummary(projectId);
  }

  @Transactional
  public IssueResponse review(int actorId, int projectId, int issueId, ReviewRequest req) {
    String role = projects.requireRole(actorId, projectId);
    requireEditorOrAbove(role);
    IssueResponse existing = issues.findById(projectId, issueId);
    if (existing == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "issue not found");
    }
    IssueStatus newStatus = parseAction(req == null ? null : req.action());
    // reopen clears the reviewer (Global Constraint); accept/reject stamps the acting reviewer.
    Integer reviewerId = newStatus == IssueStatus.OPEN ? null : actorId;
    issues.review(issueId, newStatus.name(), reviewerId);
    return issues.findById(projectId, issueId);
  }

  // Owner/editor triggers a full project recompute (ValidationService.revalidateProject, Task 1)
  // and gets back the resulting problems-view summary -- the same shape GET /issues/summary
  // returns, so a caller can show "N errors, M warnings" right after a manual revalidate.
  // Deliberately NOT @Transactional: ValidationService.revalidateProject calls revalidateUsage
  // through the Spring proxy per usage so each gets its OWN transaction (see that method's
  // javadoc) -- wrapping this method in a transaction would defeat that by making it all one
  // outer transaction again, holding every usage's advisory lock until the very end.
  public IssueSummaryResponse revalidateProject(int actorId, int projectId) {
    requireOwnerOrEditor(projects.requireRole(actorId, projectId));
    validationService.revalidateProject(projectId);
    return buildSummary(projectId);
  }

  private IssueSummaryResponse buildSummary(int projectId) {
    List<IssueMapper.StatusSeverityCount> rows = issues.countByStatusSeverity(projectId);
    Map<String, Long> byStatus = new LinkedHashMap<>();
    Map<String, Long> bySeverity = new LinkedHashMap<>();
    long total = 0;
    for (IssueMapper.StatusSeverityCount row : rows) {
      byStatus.merge(row.status().toLowerCase(Locale.ROOT), row.count(), Long::sum);
      bySeverity.merge(row.severity().toLowerCase(Locale.ROOT), row.count(), Long::sum);
      total += row.count();
    }
    return new IssueSummaryResponse(total, byStatus, bySeverity);
  }

  private static void requireEditorOrAbove(String role) {
    if (!Role.OWNER.dbValue().equals(role) && !Role.EDITOR.dbValue().equals(role)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner or editor required");
    }
  }

  private static void requireOwnerOrEditor(String role) {
    if (!Role.OWNER.dbValue().equals(role) && !Role.EDITOR.dbValue().equals(role)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner or editor required");
    }
  }

  // accept -> ACCEPTED, reject -> REJECTED, reopen -> OPEN; anything else (including a missing
  // action) is a 400, not a 500 from an unmatched switch.
  private static IssueStatus parseAction(String action) {
    if (action == null || action.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action is required");
    }
    return switch (action.trim().toLowerCase(Locale.ROOT)) {
      case "accept" -> IssueStatus.ACCEPTED;
      case "reject" -> IssueStatus.REJECTED;
      case "reopen" -> IssueStatus.OPEN;
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid action: " + action);
    };
  }

  // null/blank -> no filter; otherwise case-insensitively parsed against IssueStatus, 400 on
  // anything unrecognized (TaskStatus.fromApi's convention).
  private static String normalizeStatusFilter(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    try {
      return IssueStatus.valueOf(status.trim().toUpperCase(Locale.ROOT)).name();
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid status: " + status);
    }
  }

  private static String normalizeSeverityFilter(String severity) {
    if (severity == null || severity.isBlank()) {
      return null;
    }
    try {
      return Severity.valueOf(severity.trim().toUpperCase(Locale.ROOT)).name();
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid severity: " + severity);
    }
  }

  // entityType has no closed vocabulary (yet) -- just a plain pass-through filter, blank -> none.
  private static String normalizeEntityTypeFilter(String entityType) {
    return (entityType == null || entityType.isBlank()) ? null : entityType.trim();
  }
}
