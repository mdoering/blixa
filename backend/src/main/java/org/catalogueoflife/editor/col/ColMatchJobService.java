package org.catalogueoflife.editor.col;

import java.util.List;
import java.util.Locale;
import org.catalogueoflife.editor.col.dto.ColMatchRunResponse;
import org.catalogueoflife.editor.name.ClbMatchClient;
import org.catalogueoflife.editor.name.ColMatchService;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.NameUsageService;
import org.catalogueoflife.editor.name.dto.RankName;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectMapper;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.validation.IssueMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

// The project-wide bulk COL-match job: matchOne re-matches a single usage against COL and
// reconciles the stored col: alternativeId + a col_* issue flag; runSync iterates every usage in a
// project and tallies the outcomes into a col_match_run row; start/run are Task 3's async trigger
// (ColMatchRunController -> start, which authorizes the caller and inserts the RUNNING row on the
// request thread, then fires run() -- @Async off ColMatchAsyncConfig's dedicated single-thread pool
// -- so the request returns immediately with a 202 while the (possibly long) project-wide match
// proceeds in the background). matchOne/runSync stay deliberately off-request internally (no
// AuditService.record/ValidationEvent): the flags + the col_match_run row ARE the audit record for
// this feature.
@Service
public class ColMatchJobService {

  private static final Logger log = LoggerFactory.getLogger(ColMatchJobService.class);

  private static final String ENTITY = "name_usage";
  private static final String RULE_ADDED = "col_id_added";
  private static final String RULE_UPDATED = "col_id_updated";
  private static final String RULE_MISSING = "col_match_missing";

  private final NameUsageMapper usages;
  private final IssueMapper issues;
  private final ProjectMapper projects;
  private final ProjectService projectService;
  private final ClbMatchClient clb;
  private final ColMatchRunMapper runs;

  // Self-reference through the Spring proxy so run()'s @Async and runSync's per-usage matchOne calls
  // actually go through their proxied annotations (@Async / @Transactional) -- see start()/run()/
  // runSync() below, and ValidationService's identical `self` field for the same reason: a plain
  // `this.foo(...)` call bypasses the proxy entirely. @Lazy avoids a circular bean-creation error
  // (this bean depending on itself while still being constructed).
  @Autowired
  @Lazy
  private ColMatchJobService self;

  public ColMatchJobService(NameUsageMapper usages, IssueMapper issues, ProjectMapper projects,
      ProjectService projectService, ClbMatchClient clb, ColMatchRunMapper runs) {
    this.usages = usages;
    this.issues = issues;
    this.projects = projects;
    this.projectService = projectService;
    this.clb = clb;
    this.runs = runs;
  }

  // Authorizes the caller (owner/editor, same tier as IssueService.revalidateProject -- a write-
  // adjacent bulk action, not a read), inserts the RUNNING col_match_run row synchronously (so the
  // 202 response always has a real id to poll), and fires the async run through `self` so @Async
  // actually applies, then reads the row straight back for the response body.
  public ColMatchRunResponse start(int userId, int projectId) {
    requireOwnerOrEditor(projectService.requireRole(userId, projectId));
    ColMatchRun run = new ColMatchRun();
    run.setProjectId(projectId);
    runs.insertRunning(run);
    try {
      self.run(projectId, run.getId(), userId);
    } catch (TaskRejectedException e) {
      // The single-thread, bounded-queue (queueCapacity(50)) executor is full: self.run(...) throws
      // synchronously at this call site, before run()'s own try/catch (which maps failures to
      // runs.fail) ever gets a chance to run. Without this catch the just-inserted RUNNING row would
      // be stuck forever (nothing left to process it) and the caller would see an unhandled 500
      // instead of a clean, retryable 503.
      runs.fail(run.getId(), "match service busy — try again later");
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "COL match service busy, try again later");
    }
    return ColMatchRunResponse.of(runs.findById(run.getId()));
  }

  // Poll target for the 202's id: any project member may check progress (same "any role may read"
  // convention as IssueService.list/summary), 404 both for a non-member and for a runId that exists
  // but belongs to a different project -- never leaking another project's run.
  public ColMatchRunResponse getRun(int userId, int projectId, long runId) {
    projectService.requireRole(userId, projectId);
    ColMatchRun run = runs.findById(runId);
    if (run == null || !run.getProjectId().equals(projectId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "col-match run not found");
    }
    return ColMatchRunResponse.of(run);
  }

  private static void requireOwnerOrEditor(String role) {
    if (!Role.OWNER.dbValue().equals(role) && !Role.EDITOR.dbValue().equals(role)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner or editor required");
    }
  }

  // The async body: runs on ColMatchAsyncConfig's single-thread pool, off the request thread that
  // called start(). Any exception escaping runSync (e.g. usages.findAllIds itself failing, or a
  // runs.tick/finish write failing) marks the whole row FAILED with the exception's message rather
  // than leaving it stuck RUNNING forever -- there is no caller left to propagate the exception to
  // once we're here, same contract as ValidationTrigger's catch.
  @Async(ColMatchAsyncConfig.EXECUTOR_BEAN)
  public void run(int projectId, long runId, int userId) {
    try {
      runSync(projectId, runId, userId);
    } catch (Exception e) {
      log.warn("col-match run {} failed for project {}: {}", runId, projectId, e.getMessage(), e);
      runs.fail(runId, e.getMessage());
    }
  }

  // Re-matches one usage against COL and reconciles: VERIFIED (matched == stored, nothing to
  // write), UPDATED (stored != null but changed), ADDED (was unmatched, now matched), or UNMATCHED
  // (no COL match at all). Always clears any prior col_* flag first (idempotent -- a re-run leaves
  // at most one col_* flag per usage) then writes at most one new flag. A missing usage (deleted
  // concurrently, or a stale id) is treated as UNMATCHED with no flag, since there is no entity left
  // to flag.
  @Transactional
  public ColOutcome matchOne(int projectId, int usageId, int userId) {
    NameUsage u = usages.findByIdInProject(projectId, usageId);
    if (u == null) {
      return ColOutcome.UNMATCHED;
    }
    issues.deleteColFlags(projectId, ENTITY, usageId);
    String stored = ColMatchService.colIdFrom(u.getAlternativeId());
    Project project = projects.findById(projectId);
    // Project.nomCode is the NomCode enum (see ProjectService.parseNomCode); its name() is already
    // upper-case (ZOOLOGICAL, BOTANICAL, ...) which the CLB `code` param parses tolerantly -- same
    // derivation as ColMatchService.match.
    String code = project == null || project.getNomCode() == null ? null : project.getNomCode().name();
    String rank = u.getRank() == null ? null : u.getRank().toLowerCase(Locale.ROOT);
    List<RankName> classification = usages.findClassification(projectId, usageId);
    JsonNode root = clb.match(u.getScientificName(), u.getAuthorship(), rank, code, classification);
    String matched = bestColId(root);

    if (matched == null) {
      issues.insertColFlag(projectId, ENTITY, usageId, RULE_MISSING, "WARNING",
          "No COL match for " + u.getScientificName());
      return ColOutcome.UNMATCHED;
    }
    if (matched.equals(stored)) {
      return ColOutcome.VERIFIED;
    }
    // Add or update the id -- CAS on the version just read above (matchOne runs standalone, not
    // behind a lock, so a concurrent edit of this same usage loses the race here exactly like any
    // other optimistic-concurrency write in this app).
    List<String> merged = NameUsageService.mergeColId(u.getAlternativeId(), matched);
    int rows = usages.updateAlternativeId(projectId, usageId, merged, userId, u.getVersion());
    if (rows == 0) {
      // Lost the optimistic-lock race: the usage was edited concurrently during the CLB round-trip
      // (u.getVersion() no longer matches the row). Make no claim this run -- insert no col flag
      // and report VERIFIED (a no-op this run); the next run re-reads the now-changed row and
      // reconciles then.
      return ColOutcome.VERIFIED;
    }
    if (stored == null) {
      issues.insertColFlag(projectId, ENTITY, usageId, RULE_ADDED, "INFO", "Added col:" + matched);
      return ColOutcome.ADDED;
    }
    issues.insertColFlag(projectId, ENTITY, usageId, RULE_UPDATED, "INFO",
        "COL id changed col:" + stored + " -> col:" + matched);
    return ColOutcome.UPDATED;
  }

  // Iterates every usage in the project (findAllIds, id order), running matchOne for each and
  // tallying processed + the matching per-outcome counter into the col_match_run row. Calls through
  // `self` (the Spring proxy), not `this` -- exactly like ValidationService.revalidateProject's
  // self.revalidateUsage -- so each iteration's matchOne actually runs under its OWN @Transactional
  // proxy invocation (a plain `this.matchOne(...)` call bypasses the proxy entirely, and the whole
  // project's worth of deleteColFlags+updateAlternativeId+insertColFlag would silently collapse into
  // ONE outer transaction for the whole run instead of one per usage). runSync itself must NOT be
  // @Transactional for the same reason. A usage whose matchOne throws (e.g. ClbMatchClient surfacing
  // a 502; a lost CAS race no longer throws -- see matchOne's rows==0 branch) is counted as
  // UNMATCHED here, but matchOne itself is @Transactional, so the exception rolls back that whole
  // matchOne invocation -- including its deleteColFlags -- for that usage. The usage's prior col_*
  // flag (and prior col:<id>, if any) is therefore RETAINED, not deleted: a transient failure (e.g.
  // a CLB 502) never wipes a previously-good match. This does mean the run's UNMATCHED counter and
  // that usage's actual flag/id can disagree for this run (still showing its unchanged, still-valid
  // prior match) -- a benign counter/flag mismatch, not data loss.
  public void runSync(int projectId, long runId, int userId) {
    List<Integer> ids = usages.findAllIds(projectId);
    runs.setTotal(runId, ids.size());
    for (int id : ids) {
      ColOutcome outcome;
      try {
        outcome = self.matchOne(projectId, id, userId);
      } catch (RuntimeException e) {
        outcome = ColOutcome.UNMATCHED;
      }
      runs.tick(runId, outcome.name());
    }
    runs.finish(runId);
  }

  // The matched COL id: root.path("usage").path("id") as text, but only when the response's overall
  // `type` isn't NONE and the usage node is actually present (mirrors ColMatchService.addCandidate's
  // same two guards on the CLB /match/nameusage response shape) -- else null (no match).
  private static String bestColId(JsonNode root) {
    if ("NONE".equals(root.path("type").asString(null))) {
      return null;
    }
    JsonNode usageNode = root.path("usage");
    if (usageNode.isMissingNode() || usageNode.isNull()) {
      return null;
    }
    return usageNode.path("id").asString(null);
  }
}
