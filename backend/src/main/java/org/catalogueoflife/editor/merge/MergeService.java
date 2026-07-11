package org.catalogueoflife.editor.merge;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.catalogueoflife.editor.merge.dto.Candidate;
import org.catalogueoflife.editor.merge.dto.Category;
import org.catalogueoflife.editor.merge.dto.MappingRow;
import org.catalogueoflife.editor.merge.dto.MergePlan;
import org.catalogueoflife.editor.merge.dto.MergeRunResponse;
import org.catalogueoflife.editor.merge.dto.MergeRunResponse.MergeMetrics;
import org.catalogueoflife.editor.merge.dto.MergeRunResponse.MergeMetrics.NameCounts;
import org.catalogueoflife.editor.merge.dto.MergeRunResponse.MergeMetrics.ReferenceCounts;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.Reference;
import org.catalogueoflife.editor.name.ReferenceMapper;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

// The supervised project-merge compute-plan job: start/computePlan are the async trigger
// (MergeController -> start, which authorizes the caller and inserts the RUNNING merge_run row on
// the request thread, then fires computePlan() -- @Async off MergeAsyncConfig's dedicated
// single-thread pool -- so the request returns immediately with a 202 while the (possibly long)
// full-project match of NameMatcher/ReferenceMatcher proceeds in the background). Mirrors
// ImportRunService/ExportRunService/ColMatchJobService's start/run shape exactly, but with two
// projects to authorize instead of one: the TARGET (a write-adjacent action -- the merge will
// eventually write into it, Task 6/7) needs owner/editor, while the SOURCE only needs to be
// readable (any role, including the merge target's own membership set) -- see start()'s javadoc.
//
// There is no separate MergeApplyService in this task: apply (Task 6/7) is a later addition to
// this same class/executor, following the col_match_run precedent of one job-service class per
// async pipeline. getMapping (Task 4) and applyOverrides (Task 5) both read/rewrite the plan
// JSONB blob stored on merge_run -- there is no per-candidate table.
@Service
public class MergeService {

  private static final Logger log = LoggerFactory.getLogger(MergeService.class);
  private static final String MERGE_IN_PROGRESS = "a merge run is already in progress for this target project";

  private final MergeRunMapper runs;
  private final ProjectService projectService;
  private final NameMatcher nameMatcher;
  private final ReferenceMatcher referenceMatcher;
  private final NameUsageMapper usages;
  private final ReferenceMapper references;
  private final ObjectMapper json;

  // Self-reference through the Spring proxy so computePlan()'s @Async actually goes through the
  // proxied annotation -- see ImportRunService's identical `self` field for the same reason: a
  // plain `this.computePlan(...)` call bypasses the proxy entirely. @Lazy avoids a circular
  // bean-creation error (this bean depending on itself while still being constructed).
  @Autowired
  @Lazy
  private MergeService self;

  public MergeService(MergeRunMapper runs, ProjectService projectService, NameMatcher nameMatcher,
      ReferenceMatcher referenceMatcher, NameUsageMapper usages, ReferenceMapper references,
      ObjectMapper json) {
    this.runs = runs;
    this.projectService = projectService;
    this.nameMatcher = nameMatcher;
    this.referenceMatcher = referenceMatcher;
    this.usages = usages;
    this.references = references;
    this.json = json;
  }

  // Authorizes the caller (owner/editor on the TARGET -- a write-adjacent bulk action, same tier
  // as ColMatchJobService.start/IssueService.revalidateProject; any role at all on the SOURCE --
  // requireRole itself is the "is this project even visible to me" check, 404 for a non-member,
  // no further role gate since the source is only ever read here), rejects a source==target merge
  // (self-merge is meaningless and would corrupt the one-active-run guard's semantics), enforces
  // the one-active-run-per-target guard, inserts the RUNNING merge_run row synchronously (so the
  // 202 response always has a real id to poll), and fires the async computePlan through `self` so
  // @Async actually applies, then reads the row straight back for the response body.
  public MergeRunResponse start(int userId, int targetId, int sourceId) {
    requireOwnerOrEditor(projectService.requireRole(userId, targetId));
    projectService.requireRole(userId, sourceId); // any role -- read-only access to the source
    if (sourceId == targetId) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "source and target project must differ");
    }
    // Friendly pre-check: avoids even starting a second job for the common case (no race).
    // merge_run_active_idx (V19__merge_run.sql) is the race-proof backstop below for the
    // two-concurrent-requests case this check alone can't catch.
    if (runs.findActiveByTarget(targetId) != null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, MERGE_IN_PROGRESS);
    }
    MergeRun run = new MergeRun();
    run.setUserId(userId);
    run.setSourceProjectId((long) sourceId);
    run.setTargetProjectId((long) targetId);
    try {
      runs.insertRunning(run);
    } catch (DuplicateKeyException e) {
      // Lost the race against another concurrent start() for the same target: both passed the
      // pre-check above before either INSERT committed. merge_run_active_idx (a partial unique
      // index on target_project_id WHERE status IN ('RUNNING','APPLYING')) rejects the second
      // INSERT -- same friendly 409 as the pre-check, just reached via the DB-level backstop.
      throw new ResponseStatusException(HttpStatus.CONFLICT, MERGE_IN_PROGRESS);
    }
    try {
      self.computePlan(run.getId(), sourceId, targetId);
    } catch (TaskRejectedException e) {
      // The single-thread, bounded-queue (queueCapacity(50)) executor is full: self.computePlan(...)
      // throws synchronously at this call site, before computePlan's own try/catch (which maps
      // failures to runs.fail) ever gets a chance to run. Without this catch the just-inserted
      // RUNNING row would be stuck forever and the caller would see an unhandled 500 instead of a
      // clean, retryable 503.
      runs.fail(run.getId(), "merge service busy — try again later");
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "merge service busy, try again later");
    }
    return MergeRunResponse.of(runs.findById(run.getId()), json);
  }

  // The async body: runs on MergeAsyncConfig's single-thread pool, off the request thread that
  // called start(). Deliberately NOT @Transactional -- it only reads (NameMatcher/ReferenceMatcher
  // both do their own read-only queries) and writes exactly one row via runs.setPlanned/runs.fail,
  // each already its own atomic UPDATE; wrapping the whole (possibly long) two-matcher pass in one
  // transaction would hold a connection the entire time for no benefit. Any exception escaping the
  // matchers or the JSON serialization marks the whole row FAILED with the exception's message
  // rather than leaving it stuck RUNNING forever -- there is no caller left to propagate the
  // exception to once we're here, same contract as ImportRunService.run/ColMatchJobService.run.
  @Async(MergeAsyncConfig.EXECUTOR_BEAN)
  public void computePlan(long runId, int sourceId, int targetId) {
    try {
      List<Candidate> refs = referenceMatcher.match(sourceId, targetId);
      List<Candidate> names = nameMatcher.match(sourceId, targetId);

      MergeMetrics metrics = buildMetrics(sourceId, refs, names);
      MergePlan plan = new MergePlan(refs, names);
      runs.setPlanned(runId, json.writeValueAsString(plan), json.writeValueAsString(metrics));
    } catch (Exception e) {
      log.warn("merge compute-plan run {} failed (source {}, target {}): {}",
          runId, sourceId, targetId, e.getMessage(), e);
      runs.fail(runId, e.getMessage());
    }
  }

  // Per-category counts for both halves of the plan, plus the run-wide newAccepted/newSynonyms
  // breakdown of the NEW name candidates (looked up against the SOURCE usage's status -- the
  // status a NEW record will be imported as at apply-time). Category#POSSIBLE (an ambiguous
  // exact-key match -- NameMatcher's only use of it) has no dedicated bucket in
  // MergeRunResponse.MergeMetrics.NameCounts, so it's folded into possibleFuzzy: both are "more
  // than one plausible read, needs a curator's pick", the same review-only treatment. unanchored
  // is always 0 here -- whether a NEW accepted name's source parent resolves to a stable target
  // usage can only be determined once the merge actually walks the classification, i.e. at
  // apply-time (Task 6/7); this compute-plan job never writes anything into the target.
  private MergeMetrics buildMetrics(int sourceId, List<Candidate> refs, List<Candidate> names) {
    Map<String, Status> sourceStatus = usages.findAllByProject(sourceId).stream()
        .collect(Collectors.toMap(u -> String.valueOf(u.getId()), NameUsage::getStatus, (a, b) -> a));

    int nNew = 0, nMatched = 0, nPossibleHomonym = 0, nPossibleFuzzy = 0;
    int newAccepted = 0, newSynonyms = 0;
    for (Candidate c : names) {
      switch (c.category()) {
        case NEW -> {
          nNew++;
          if (sourceStatus.get(c.sourceId()) == Status.ACCEPTED) {
            newAccepted++;
          } else {
            newSynonyms++;
          }
        }
        case MATCHED -> nMatched++;
        case POSSIBLE_HOMONYM -> nPossibleHomonym++;
        case POSSIBLE_FUZZY, POSSIBLE -> nPossibleFuzzy++;
      }
    }

    int rNew = 0, rMatched = 0, rPossible = 0;
    for (Candidate c : refs) {
      switch (c.category()) {
        case NEW -> rNew++;
        case MATCHED -> rMatched++;
        case POSSIBLE, POSSIBLE_FUZZY, POSSIBLE_HOMONYM -> rPossible++;
      }
    }

    return new MergeMetrics(
        new NameCounts(nNew, nMatched, nPossibleHomonym, nPossibleFuzzy),
        new ReferenceCounts(rNew, rMatched, rPossible),
        newAccepted, newSynonyms,
        0); // unanchored -- see this method's javadoc; computed at apply-time.
  }

  // Poll target for the 202's id: any TARGET member may read (same "any role may read" convention
  // as ColMatchJobService.getRun/ExportRunService.get), 404 both for a non-member and for a runId
  // that exists but belongs to a different target project -- never leaking another project's run.
  public MergeRunResponse get(int userId, int targetId, long runId) {
    projectService.requireRole(userId, targetId);
    MergeRun run = requireRunInTarget(targetId, runId);
    return MergeRunResponse.of(run, json);
  }

  // Latest-run view (load-on-mount for the merge review page): any TARGET member may read, returns
  // null (-> 204 at the controller) rather than 404 when the target has never had a merge run --
  // distinct from get's 404, which signals a runId that doesn't belong to this target at all.
  public MergeRunResponse latest(int userId, int targetId) {
    projectService.requireRole(userId, targetId);
    MergeRun run = runs.findLatestByTarget(targetId);
    return run == null ? null : MergeRunResponse.of(run, json);
  }

  // The paged, display-enriched mapping-review read (GET .../{runId}/mapping): parses the run's
  // stored plan JSONB, picks the `names` or `references` half, filters by category (when given),
  // pages the filtered slice in memory -- the plan is one JSONB blob for the whole run, there is no
  // per-candidate table to page via SQL -- and enriches each row with source/target display labels
  // by looking up the source (and, for a matched/possible row, target) project's full name-usage or
  // reference list ONCE per call (id -> record maps), so the review table needs no extra fetch of
  // its own. Returns an empty list (not 404/400) for a run that hasn't reached PLANNED yet (plan is
  // still null) -- the caller is expected to poll get()/latest() for status and only request the
  // mapping once PLANNED.
  public List<MappingRow> getMapping(int userId, int targetId, long runId, String entity,
      String category, int page, int size) {
    projectService.requireRole(userId, targetId);
    MergeRun run = requireRunInTarget(targetId, runId);
    if (run.getPlan() == null || run.getPlan().isBlank()) {
      return List.of();
    }

    boolean isName = "name".equalsIgnoreCase(entity);
    if (!isName && !"reference".equalsIgnoreCase(entity)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "entity must be 'name' or 'reference'");
    }
    Category cat = parseCategory(category);

    MergePlan plan = json.readValue(run.getPlan(), MergePlan.class);
    List<Candidate> all = isName ? plan.names() : plan.references();
    List<Candidate> filtered = cat == null ? all : all.stream().filter(c -> c.category() == cat).toList();

    int pageSize = Math.max(size, 1);
    int from = Math.min(Math.max(page, 0) * pageSize, filtered.size());
    int to = Math.min(from + pageSize, filtered.size());
    List<Candidate> pageSlice = filtered.subList(from, to);

    int sourceId = run.getSourceProjectId().intValue();
    if (isName) {
      Map<String, NameUsage> bySource = byId(usages.findAllByProject(sourceId), u -> String.valueOf(u.getId()));
      Map<String, NameUsage> byTarget = byId(usages.findAllByProject(targetId), u -> String.valueOf(u.getId()));
      return pageSlice.stream()
          .map(c -> new MappingRow(c.sourceId(), c.category(), c.targetId(), c.score(),
              nameLabel(bySource.get(c.sourceId())), nameLabel(byTarget.get(c.targetId()))))
          .toList();
    }
    Map<String, Reference> bySource = byId(references.findAllByProject(sourceId), r -> String.valueOf(r.getId()));
    Map<String, Reference> byTarget = byId(references.findAllByProject(targetId), r -> String.valueOf(r.getId()));
    return pageSlice.stream()
        .map(c -> new MappingRow(c.sourceId(), c.category(), c.targetId(), c.score(),
            referenceLabel(bySource.get(c.sourceId())), referenceLabel(byTarget.get(c.targetId()))))
        .toList();
  }

  private MergeRun requireRunInTarget(int targetId, long runId) {
    MergeRun run = runs.findById(runId);
    if (run == null || !Long.valueOf(targetId).equals(run.getTargetProjectId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "merge run not found");
    }
    return run;
  }

  private static Category parseCategory(String category) {
    if (category == null || category.isBlank()) {
      return null;
    }
    try {
      return Category.valueOf(category.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid category: " + category);
    }
  }

  private static <T> Map<String, T> byId(List<T> rows, Function<T, String> idOf) {
    // first-wins on a (shouldn't-happen) duplicate id rather than throwing -- a display label is
    // best-effort, never the source of truth for the merge itself.
    return rows.stream().collect(Collectors.toMap(idOf, r -> r, (a, b) -> a));
  }

  // "<scientificName> <authorship> (<rank>)", per the plan doc's mapping-review shape -- null for
  // a row with no resolvable usage (e.g. targetId is null for category NEW).
  private static String nameLabel(NameUsage u) {
    if (u == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder(u.getScientificName() == null ? "" : u.getScientificName());
    if (u.getAuthorship() != null && !u.getAuthorship().isBlank()) {
      sb.append(' ').append(u.getAuthorship());
    }
    if (u.getRank() != null && !u.getRank().isBlank()) {
      sb.append(" (").append(u.getRank()).append(')');
    }
    return sb.toString();
  }

  private static String referenceLabel(Reference r) {
    return r == null ? null : r.getCitation();
  }

  private static void requireOwnerOrEditor(String role) {
    if (!Role.OWNER.dbValue().equals(role) && !Role.EDITOR.dbValue().equals(role)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner or editor required");
    }
  }
}
