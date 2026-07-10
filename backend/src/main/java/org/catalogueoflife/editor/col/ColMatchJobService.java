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
import org.catalogueoflife.editor.project.IdentifierScope;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectMapper;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.validation.Issue;
import org.catalogueoflife.editor.validation.IssueMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

// The project-wide bulk match job: matchOneScope re-matches a single usage against ONE of the
// project's configured matchable identifier scopes (an IdentifierScope whose datasetKey is
// non-blank -- see Project.getIdentifierScopes) and reconciles the stored <scope>: alternativeId +
// a <scope>_id_* issue flag; runSync iterates every usage x every matchable scope in a project and
// tallies the outcomes into a col_match_run row (one row still covers the whole run, across all
// scopes). start/run are the async trigger (ColMatchRunController -> start, which authorizes the
// caller and inserts the RUNNING row on the request thread, then fires run() -- @Async off
// ColMatchAsyncConfig's dedicated single-thread pool -- so the request returns immediately with a
// 202 while the (possibly long) project-wide match proceeds in the background). matchOneScope/
// runSync stay deliberately off-request internally (no AuditService.record/ValidationEvent): the
// flags + the col_match_run row ARE the audit record for this feature.
@Service
public class ColMatchJobService {

  private static final Logger log = LoggerFactory.getLogger(ColMatchJobService.class);

  private static final String ENTITY = "name_usage";

  private final NameUsageMapper usages;
  private final IssueMapper issues;
  private final ProjectMapper projects;
  private final ProjectService projectService;
  private final ClbMatchClient clb;
  private final ColMatchRunMapper runs;

  // Self-reference through the Spring proxy so run()'s @Async and runSync's per-usage-per-scope
  // matchOneScope calls actually go through their proxied annotations (@Async / @Transactional) --
  // see start()/run()/runSync() below, and ValidationService's identical `self` field for the same
  // reason: a plain `this.foo(...)` call bypasses the proxy entirely. @Lazy avoids a circular
  // bean-creation error (this bean depending on itself while still being constructed).
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

  private static final String RUN_IN_PROGRESS = "a COL match run is already in progress for this project";

  // Authorizes the caller (owner/editor, same tier as IssueService.revalidateProject -- a write-
  // adjacent bulk action, not a read), enforces the one-active-run-per-project guard, inserts the
  // RUNNING col_match_run row synchronously (so the 202 response always has a real id to poll), and
  // fires the async run through `self` so @Async actually applies, then reads the row straight back
  // for the response body.
  public ColMatchRunResponse start(int userId, int projectId) {
    requireOwnerOrEditor(projectService.requireRole(userId, projectId));
    // Friendly pre-check: avoids the multi-minute job even starting a second time for the common
    // case (no race). col_match_run_active_idx (V13) is the race-proof backstop below for the two-
    // concurrent-requests case this check alone can't catch.
    if (runs.findRunningByProject(projectId) != null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, RUN_IN_PROGRESS);
    }
    ColMatchRun run = new ColMatchRun();
    run.setProjectId(projectId);
    try {
      runs.insertRunning(run);
    } catch (DuplicateKeyException e) {
      // Lost the race against another concurrent start() for the same project: both passed the
      // pre-check above before either INSERT committed. col_match_run_active_idx (a partial unique
      // index on project_id WHERE status='RUNNING') rejects the second INSERT -- same friendly 409
      // as the pre-check, just reached via the DB-level backstop instead.
      throw new ResponseStatusException(HttpStatus.CONFLICT, RUN_IN_PROGRESS);
    }
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

  // Latest-run view (ProjectMetadataPage load-on-mount): any project member may read (same "any
  // role may read" convention as getRun above), returns null (-> 204 at the controller) rather than
  // 404 when the project has never had a run -- distinct from getRun's 404, which signals a runId
  // that doesn't belong to this project at all.
  public ColMatchRunResponse latest(int userId, int projectId) {
    projectService.requireRole(userId, projectId);
    ColMatchRun run = runs.findLatestByProject(projectId);
    return run == null ? null : ColMatchRunResponse.of(run);
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

  // Re-matches one usage against ONE matchable scope's CLB dataset and reconciles: VERIFIED
  // (matched == stored, nothing to write), UPDATED (stored != null but changed), ADDED (was
  // unmatched, now matched), or UNMATCHED (no match at all in this scope). The outcome/flag-to-write
  // is computed first (newRule/severity/message, newRule==null for VERIFIED), then
  // reconcileScopeFlag below reconciles it against whatever <scope>_id_* flag(s) already exist for
  // the usage IN THIS SCOPE -- the SAME rule recurring is PRESERVED as-is (a curator's ACCEPTED
  // review of a still-firing <scope>_id_missing survives a re-run instead of being reset to OPEN),
  // mirroring ValidationService.revalidateUsage's reopen/updateFinding reconcile rather than an
  // unconditional deleteScopeFlags-then-insert. A missing usage (deleted concurrently, or a stale
  // id) is treated as UNMATCHED with no flag reconcile at all, since there is no entity left to
  // flag.
  @Transactional
  public ColOutcome matchOneScope(int projectId, int usageId, IdentifierScope scope, int userId) {
    NameUsage u = usages.findByIdInProject(projectId, usageId);
    if (u == null) {
      return ColOutcome.UNMATCHED;
    }
    String stored = ColMatchService.scopedIdFrom(u.getAlternativeId(), scope.scope());
    Project project = projects.findById(projectId);
    // Project.nomCode is the NomCode enum (see ProjectService.parseNomCode); its name() is already
    // upper-case (ZOOLOGICAL, BOTANICAL, ...) which the CLB `code` param parses tolerantly -- same
    // derivation as ColMatchService.match.
    String code = project == null || project.getNomCode() == null ? null : project.getNomCode().name();
    String rank = u.getRank() == null ? null : u.getRank().toLowerCase(Locale.ROOT);
    List<RankName> classification = usages.findClassification(projectId, usageId);
    JsonNode root = clb.match(scope.datasetKey(), u.getScientificName(), u.getAuthorship(), rank,
        code, classification);
    String matched = bestColId(root);

    // Lower-case the scope for the rule keys so they match the CURIE prefix casing (scopedIdFrom /
    // mergeScopedId lower-case it too) -- a project configuring `IPNI` stores `ipni:<id>` and
    // `ipni_id_*` flags consistently, not `IPNI_id_*`.
    String scopeKey = scope.scope().toLowerCase(Locale.ROOT);
    String addedRule = scopeKey + "_id_added";
    String updatedRule = scopeKey + "_id_updated";
    String missingRule = scopeKey + "_id_missing";

    ColOutcome outcome;
    String newRule = null;
    String severity = null;
    String message = null;

    if (matched == null) {
      newRule = missingRule;
      severity = "WARNING";
      message = "No " + scopeKey + " match for " + u.getScientificName();
      outcome = ColOutcome.UNMATCHED;
    } else if (matched.equals(stored)) {
      outcome = ColOutcome.VERIFIED;
    } else {
      // Add or update the id -- CAS on the version just read above (matchOneScope runs standalone,
      // not behind a lock, so a concurrent edit of this same usage loses the race here exactly like
      // any other optimistic-concurrency write in this app).
      List<String> merged = NameUsageService.mergeScopedId(u.getAlternativeId(), scopeKey, matched);
      int rows = usages.updateAlternativeId(projectId, usageId, merged, userId, u.getVersion());
      if (rows == 0) {
        // Lost the optimistic-lock race: the usage was edited concurrently during the CLB round-trip
        // (u.getVersion() no longer matches the row). Make no claim this run -- insert no flag and
        // report VERIFIED (a no-op this run); the next run re-reads the now-changed row and
        // reconciles then.
        outcome = ColOutcome.VERIFIED;
      } else if (stored == null) {
        newRule = addedRule;
        severity = "INFO";
        message = "Added " + scopeKey + ":" + matched;
        outcome = ColOutcome.ADDED;
      } else {
        newRule = updatedRule;
        severity = "INFO";
        message = scopeKey + " id changed " + scopeKey + ":" + stored + " -> " + scopeKey + ":" + matched;
        outcome = ColOutcome.UPDATED;
      }
    }

    reconcileScopeFlag(projectId, usageId, scope, newRule, severity, message);
    return outcome;
  }

  // Reconciles the usage's existing <scope>_id_* flag(s) (normally 0 or 1, via
  // IssueMapper.findScopeFlags scoped to THIS scope's three rule keys) against this run's target
  // flag (newRule==null means "no flag warranted" -- VERIFIED, both the stored==matched path and
  // the CAS-miss path). The SAME rule recurring is kept untouched (its review status, e.g.
  // ACCEPTED, survives); anything else found (resolved, a different rule, or a stray duplicate) is
  // deleted; a genuinely new rule is inserted OPEN. This is the status-preserving counterpart to
  // ValidationService.revalidateUsage's reopen/updateFinding reconcile, scoped to the three
  // <scope>_id_* rule keys that method deliberately ignores (see its `ruleKeys` guard).
  private void reconcileScopeFlag(int projectId, int usageId, IdentifierScope scope, String newRule,
      String severity, String message) {
    String scopeKey = scope.scope();
    List<Issue> existing = issues.findScopeFlags(projectId, ENTITY, usageId,
        scopeKey + "_id_added", scopeKey + "_id_updated", scopeKey + "_id_missing");
    boolean keptMatching = false;
    for (Issue e : existing) {
      if (newRule != null && e.getRule().equals(newRule) && !keptMatching) {
        keptMatching = true; // same flag recurs -> preserve it (status untouched)
      } else {
        issues.deleteById(e.getId()); // resolved / different-rule / stray duplicate -> remove
      }
    }
    if (newRule != null && !keptMatching) {
      issues.insertColFlag(projectId, ENTITY, usageId, newRule, severity, message); // new -> OPEN
    }
  }

  // Iterates every usage in the project (findAllIds, id order) x every matchable scope (the
  // project's configured identifierScopes filtered to a non-blank datasetKey), running
  // matchOneScope for each usage x scope pair and tallying processed + the matching per-outcome
  // counter into the col_match_run row (total = usages x matchableScopes; a project with no
  // matchable scope finishes immediately with total 0, the nested loop simply never running). Calls
  // through `self` (the Spring proxy), not `this` -- exactly like ValidationService.revalidateProject's
  // self.revalidateUsage -- so each iteration's matchOneScope actually runs under its OWN
  // @Transactional proxy invocation (a plain `this.matchOneScope(...)` call bypasses the proxy
  // entirely, and the whole project's worth of reconcileScopeFlag+updateAlternativeId+insertColFlag
  // would silently collapse into ONE outer transaction for the whole run instead of one per usage x
  // scope). runSync itself must NOT be @Transactional for the same reason. A usage x scope pair
  // whose matchOneScope throws (e.g. ClbMatchClient surfacing a 502; a lost CAS race no longer
  // throws -- see matchOneScope's rows==0 branch) is counted as UNMATCHED here, but matchOneScope
  // itself is @Transactional, so the exception rolls back that whole matchOneScope invocation --
  // including its reconcileScopeFlag deletes/inserts -- for that usage x scope pair. The usage's
  // prior <scope>_id_* flag (and prior <scope>:<id>, if any) is therefore RETAINED, not deleted: a
  // transient failure (e.g. a CLB 502) never wipes a previously-good match. This does mean the run's
  // UNMATCHED counter and that usage's actual flag/id can disagree for this run (still showing its
  // unchanged, still-valid prior match) -- a benign counter/flag mismatch, not data loss.
  public void runSync(int projectId, long runId, int userId) {
    Project project = projects.findById(projectId);
    List<IdentifierScope> matchable = matchableScopes(project);
    List<Integer> ids = usages.findAllIds(projectId);
    runs.setTotal(runId, ids.size() * matchable.size());
    for (int id : ids) {
      for (IdentifierScope scope : matchable) {
        ColOutcome outcome;
        try {
          outcome = self.matchOneScope(projectId, id, scope, userId);
        } catch (RuntimeException e) {
          outcome = ColOutcome.UNMATCHED;
        }
        runs.tick(runId, outcome.name());
      }
    }
    runs.finish(runId);
  }

  // A scope is matchable iff its datasetKey is non-blank (see IdentifierScope's javadoc); a project
  // with no identifierScopes configured at all (null, the default for a freshly created project)
  // has none.
  private static List<IdentifierScope> matchableScopes(Project project) {
    if (project == null || project.getIdentifierScopes() == null) {
      return List.of();
    }
    return project.getIdentifierScopes().stream()
        // A blank/absent scope name can only arrive via a direct API POST (the UI drops blank-scope
        // rows); guard it here too so a `{datasetKey}`-only entry can't become "matchable" and then
        // NPE in mergeScopedId / write a `:<id>` CURIE and `null_id_*` flags.
        .filter(s -> s.scope() != null && !s.scope().isBlank())
        .filter(s -> s.datasetKey() != null && !s.datasetKey().isBlank())
        .toList();
  }

  // The matched id: root.path("usage").path("id") as text, but only when the response's overall
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
