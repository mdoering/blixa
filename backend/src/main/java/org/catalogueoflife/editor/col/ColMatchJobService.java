package org.catalogueoflife.editor.col;

import java.util.List;
import java.util.Locale;
import org.catalogueoflife.editor.name.ClbMatchClient;
import org.catalogueoflife.editor.name.ColMatchService;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.NameUsageService;
import org.catalogueoflife.editor.name.dto.RankName;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectMapper;
import org.catalogueoflife.editor.validation.IssueMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

// The synchronous, testable core of the project-wide bulk COL-match job: matchOne re-matches a
// single usage against COL and reconciles the stored col: alternativeId + a col_* issue flag;
// runSync iterates every usage in a project and tallies the outcomes into a col_match_run row.
// Deliberately off-request (no ProjectService auth check, no AuditService.record/ValidationEvent):
// this runs from an async job (Task 3 wraps runSync in @Async off a controller that has already
// authorized the caller), where there is no HTTP request to hang CurrentTask/RequestContextHolder
// off of -- the flags + the col_match_run row ARE the audit record for this feature.
@Service
public class ColMatchJobService {

  private static final String ENTITY = "name_usage";
  private static final String RULE_ADDED = "col_id_added";
  private static final String RULE_UPDATED = "col_id_updated";
  private static final String RULE_MISSING = "col_match_missing";

  private final NameUsageMapper usages;
  private final IssueMapper issues;
  private final ProjectMapper projects;
  private final ClbMatchClient clb;
  private final ColMatchRunMapper runs;

  public ColMatchJobService(NameUsageMapper usages, IssueMapper issues, ProjectMapper projects,
      ClbMatchClient clb, ColMatchRunMapper runs) {
    this.usages = usages;
    this.issues = issues;
    this.projects = projects;
    this.clb = clb;
    this.runs = runs;
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
  // tallying processed + the matching per-outcome counter into the col_match_run row. A usage whose
  // matchOne throws (e.g. ClbMatchClient surfacing a 502; a lost CAS race no longer throws -- see
  // matchOne's rows==0 branch) is counted as UNMATCHED rather than aborting the whole run -- by the
  // time the exception is thrown, deleteColFlags has already run for that usage (matchOne's first
  // statement), so its col_* flag is simply gone with no replacement, which is the correct "we
  // don't know" state for that usage.
  public void runSync(int projectId, long runId, int userId) {
    List<Integer> ids = usages.findAllIds(projectId);
    runs.setTotal(runId, ids.size());
    for (int id : ids) {
      ColOutcome outcome;
      try {
        outcome = matchOne(projectId, id, userId);
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
