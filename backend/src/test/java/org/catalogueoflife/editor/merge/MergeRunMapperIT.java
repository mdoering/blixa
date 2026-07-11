package org.catalogueoflife.editor.merge;

import static org.assertj.core.api.Assertions.assertThat;

import org.catalogueoflife.editor.merge.dto.MergeRunResponse;
import org.catalogueoflife.editor.merge.dto.MergeRunResponse.MergeIssue;
import org.catalogueoflife.editor.merge.dto.MergeRunResponse.MergeMetrics;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectMapper;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.gbif.nameparser.api.NomCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

// Mirrors ImportRunMapperIT's shape (a plain mapper-level IT against the real, shared
// testcontainers Postgres -- see AbstractPostgresIT) rather than a MockMvc-driven one: Task 1 adds
// no controller, so this exercises MergeRunMapper directly. Each test seeds its own source+target
// project pair (merge_run_target_idx / findLatestByTarget / findActiveByTarget scope by
// target_project_id), so tests don't interfere with each other despite sharing one DB for the
// whole JVM's test run.
class MergeRunMapperIT extends AbstractPostgresIT {

  // A fully-populated, all-zero MergeMetrics JSON blob -- every field is an int primitive (see
  // MergeRunResponse.MergeMetrics), so a partial/empty object ("{}") fails to deserialize
  // (FAIL_ON_NULL_FOR_PRIMITIVES). Production code (MergeService, a later task) always builds a
  // complete metrics object; tests that don't care about specific counts reuse this rather than
  // an under-specified one.
  private static final String EMPTY_METRICS_JSON =
      "{\"names\":{\"new\":0,\"matched\":0,\"possibleHomonym\":0,\"possibleFuzzy\":0},"
      + "\"references\":{\"new\":0,\"matched\":0,\"possible\":0},"
      + "\"newAccepted\":0,\"newSynonyms\":0,\"unanchored\":0}";
  private static final String EMPTY_PLAN_JSON = "{\"references\":[],\"names\":[]}";

  @Autowired MergeRunMapper runs;
  @Autowired AppUserMapper users;
  @Autowired ProjectMapper projects;

  private Integer newUser(String username) {
    AppUser u = new AppUser();
    u.setUsername(username);
    users.insert(u);
    return u.getId();
  }

  private Long newProject(String title) {
    Project p = new Project();
    p.setTitle(title);
    p.setNomCode(NomCode.ZOOLOGICAL);
    projects.insert(p);
    return p.getId().longValue();
  }

  private MergeRun newRunning(int userId, long sourceProjectId, long targetProjectId) {
    MergeRun run = new MergeRun();
    run.setUserId(userId);
    run.setSourceProjectId(sourceProjectId);
    run.setTargetProjectId(targetProjectId);
    runs.insertRunning(run);
    return run;
  }

  @Test
  void insertRunningThenFindById() {
    int userId = newUser("mergeRunOwner1");
    long sourceId = newProject("mergeSource1");
    long targetId = newProject("mergeTarget1");
    MergeRun run = newRunning(userId, sourceId, targetId);
    assertThat(run.getId()).isNotNull();

    MergeRun found = runs.findById(run.getId());
    assertThat(found).isNotNull();
    assertThat(found.getUserId()).isEqualTo(userId);
    assertThat(found.getSourceProjectId()).isEqualTo(sourceId);
    assertThat(found.getTargetProjectId()).isEqualTo(targetId);
    assertThat(found.getStatus()).isEqualTo("RUNNING");
    assertThat(found.getMode()).isNull();
    assertThat(found.getTransactional()).isNull();
    assertThat(found.getPlan()).isNull();
    assertThat(found.getMetrics()).isNull();
    assertThat(found.getIssues()).isNull();
    assertThat(found.getStartedAt()).isNotNull();
    assertThat(found.getPlannedAt()).isNull();
    assertThat(found.getFinishedAt()).isNull();
    assertThat(found.getError()).isNull();
  }

  @Test
  void setPlannedStoresPlanAndMetricsAndMovesToPlanned() throws Exception {
    int userId = newUser("mergeRunOwner2");
    long sourceId = newProject("mergeSource2");
    long targetId = newProject("mergeTarget2");
    MergeRun run = newRunning(userId, sourceId, targetId);

    String planJson = "{\"references\":[],\"names\":[{\"sourceId\":\"s1\",\"category\":\"NEW\","
        + "\"targetId\":null,\"score\":null}]}";
    String metricsJson = "{\"names\":{\"new\":1,\"matched\":0,\"possibleHomonym\":0,\"possibleFuzzy\":0},"
        + "\"references\":{\"new\":0,\"matched\":0,\"possible\":0},"
        + "\"newAccepted\":1,\"newSynonyms\":0,\"unanchored\":0}";
    int updated = runs.setPlanned(run.getId(), planJson, metricsJson);
    assertThat(updated).isEqualTo(1);

    MergeRun found = runs.findById(run.getId());
    assertThat(found.getStatus()).isEqualTo("PLANNED");
    assertThat(found.getPlan()).isNotNull();
    assertThat(found.getMetrics()).isNotNull();
    assertThat(found.getPlannedAt()).isNotNull();

    // Round-trip through MergeRunResponse.of exactly as an API layer would, proving the metrics
    // JSONB column is retrievable as the same MergeMetrics shape it was written with.
    MergeRunResponse response = MergeRunResponse.of(found, new ObjectMapper());
    MergeMetrics metrics = response.metrics();
    assertThat(metrics.names().newCount()).isEqualTo(1);
    assertThat(metrics.newAccepted()).isEqualTo(1);
    assertThat(response.issues()).isEmpty();
  }

  @Test
  void updatePlanOverwritesStoredPlan() {
    int userId = newUser("mergeRunOwner3");
    long sourceId = newProject("mergeSource3");
    long targetId = newProject("mergeTarget3");
    MergeRun run = newRunning(userId, sourceId, targetId);

    runs.setPlanned(run.getId(), EMPTY_PLAN_JSON, EMPTY_METRICS_JSON);
    String overriddenPlan = "{\"references\":[],\"names\":[{\"sourceId\":\"s1\",\"category\":\"MATCHED\","
        + "\"targetId\":\"t1\",\"score\":1.0}]}";
    int updated = runs.updatePlan(run.getId(), overriddenPlan);
    assertThat(updated).isEqualTo(1);

    MergeRun found = runs.findById(run.getId());
    assertThat(found.getPlan()).contains("MATCHED").contains("t1");
    // updatePlan does not touch status
    assertThat(found.getStatus()).isEqualTo("PLANNED");
  }

  @Test
  void startApplySetsModeTransactionalAndApplying() {
    int userId = newUser("mergeRunOwner4");
    long sourceId = newProject("mergeSource4");
    long targetId = newProject("mergeTarget4");
    MergeRun run = newRunning(userId, sourceId, targetId);
    runs.setPlanned(run.getId(), EMPTY_PLAN_JSON, EMPTY_METRICS_JSON);

    int updated = runs.startApply(run.getId(), "OVERWRITE", true);
    assertThat(updated).isEqualTo(1);

    MergeRun found = runs.findById(run.getId());
    assertThat(found.getStatus()).isEqualTo("APPLYING");
    assertThat(found.getMode()).isEqualTo("OVERWRITE");
    assertThat(found.getTransactional()).isTrue();
  }

  // Fix 1 (review, data corruption): startApply's "AND status = 'PLANNED'" WHERE guard is what
  // makes PLANNED -> APPLYING an atomic compare-and-swap rather than a plain unconditional UPDATE.
  // A double-submitted apply (two concurrent requests both passing MergeApplyService.apply's
  // in-memory PLANNED pre-check before either has written anything) must not both succeed here --
  // otherwise both would go on to enqueue the async worker and the stored plan would be applied
  // TWICE (every NEW reference/usage inserted twice). This can't be reproduced with real concurrent
  // threads deterministically in an IT, but the mapper-level guard itself is fully exercised by two
  // sequential calls: the run is PLANNED once, so only the FIRST startApply can ever match a row.
  @Test
  void startApplyIsAnAtomicCasThatOnlyTheFirstCallerWins() {
    int userId = newUser("mergeRunOwner4cas");
    long sourceId = newProject("mergeSource4cas");
    long targetId = newProject("mergeTarget4cas");
    MergeRun run = newRunning(userId, sourceId, targetId);
    runs.setPlanned(run.getId(), EMPTY_PLAN_JSON, EMPTY_METRICS_JSON);

    // First caller: PLANNED -> APPLYING, wins the CAS (rowcount 1).
    int firstCaller = runs.startApply(run.getId(), "OVERWRITE", true);
    assertThat(firstCaller).isEqualTo(1);
    assertThat(runs.findById(run.getId()).getStatus()).isEqualTo("APPLYING");

    // Second caller (simulating a losing racer that also read PLANNED before the first UPDATE
    // committed): the row is no longer PLANNED, so its UPDATE matches zero rows -- MergeApplyService
    // must treat this 0 as "already claimed" and refuse to enqueue a second worker.
    int secondCaller = runs.startApply(run.getId(), "FILL_GAPS", false);
    assertThat(secondCaller).isEqualTo(0);

    // The losing caller's mode/transactional must NOT have overwritten the winner's -- the row is
    // untouched by the no-op UPDATE.
    MergeRun found = runs.findById(run.getId());
    assertThat(found.getStatus()).isEqualTo("APPLYING");
    assertThat(found.getMode()).isEqualTo("OVERWRITE");
    assertThat(found.getTransactional()).isTrue();
  }

  // Fix 1 (final-review, data corruption via TOCTOU): updatePlanAndMetrics is the status-guarded
  // write MergeService.applyOverrides uses instead of the unconditional setPlanned. Same "prove the
  // CAS via sequential calls" discipline as startApplyIsAnAtomicCasThatOnlyTheFirstCallerWins above:
  // once the run has left PLANNED (APPLYING here), the guarded UPDATE's "AND status = 'PLANNED'"
  // WHERE clause must match zero rows and must NOT have written the new plan/metrics -- an override
  // that read the row while it was still PLANNED and only now (post-fetch) discovers a concurrent
  // apply worker has since claimed it must lose cleanly, not silently reset APPLYING back to PLANNED
  // and overwrite the in-flight plan.
  @Test
  void updatePlanAndMetricsRefusesToWriteAnApplyingRun() {
    int userId = newUser("mergeRunOwner4pm");
    long sourceId = newProject("mergeSource4pm");
    long targetId = newProject("mergeTarget4pm");
    MergeRun run = newRunning(userId, sourceId, targetId);
    runs.setPlanned(run.getId(), EMPTY_PLAN_JSON, EMPTY_METRICS_JSON);

    int cas = runs.startApply(run.getId(), "OVERWRITE", true);
    assertThat(cas).isEqualTo(1);
    // Captured AFTER the round trip through the jsonb column (Postgres reformats/reorders JSON text
    // on storage -- e.g. key order becomes shortest-key-first -- so comparing against the original
    // literal constants would spuriously fail; compare DB-stored value to DB-stored value instead,
    // same discipline updatePlanOverwritesStoredPlan's .contains(...) assertion below sidesteps).
    MergeRun beforeAttempt = runs.findById(run.getId());
    assertThat(beforeAttempt.getStatus()).isEqualTo("APPLYING");

    String overriddenPlan = "{\"references\":[],\"names\":[{\"sourceId\":\"s1\",\"category\":\"MATCHED\","
        + "\"targetId\":\"t1\",\"score\":1.0}]}";
    String overriddenMetrics = "{\"names\":{\"new\":0,\"matched\":1,\"possibleHomonym\":0,\"possibleFuzzy\":0},"
        + "\"references\":{\"new\":0,\"matched\":0,\"possible\":0},"
        + "\"newAccepted\":0,\"newSynonyms\":0,\"unanchored\":0}";
    int updated = runs.updatePlanAndMetrics(run.getId(), overriddenPlan, overriddenMetrics);
    assertThat(updated).isEqualTo(0);

    // Neither the status nor the plan/metrics were touched by the losing write.
    MergeRun found = runs.findById(run.getId());
    assertThat(found.getStatus()).isEqualTo("APPLYING");
    assertThat(found.getPlan()).isEqualTo(beforeAttempt.getPlan());
    assertThat(found.getMetrics()).isEqualTo(beforeAttempt.getMetrics());
  }

  // Same CAS, terminal DONE side: a stray override arriving after the apply already finished must
  // not resurrect/rewrite the row either.
  @Test
  void updatePlanAndMetricsRefusesToWriteADoneRun() {
    int userId = newUser("mergeRunOwner4pmDone");
    long sourceId = newProject("mergeSource4pmDone");
    long targetId = newProject("mergeTarget4pmDone");
    MergeRun run = newRunning(userId, sourceId, targetId);
    runs.setPlanned(run.getId(), EMPTY_PLAN_JSON, EMPTY_METRICS_JSON);
    runs.startApply(run.getId(), "OVERWRITE", true);
    runs.finish(run.getId(), null);
    MergeRun beforeAttempt = runs.findById(run.getId());
    assertThat(beforeAttempt.getStatus()).isEqualTo("DONE");

    int updated = runs.updatePlanAndMetrics(run.getId(),
        "{\"references\":[],\"names\":[]}", EMPTY_METRICS_JSON);
    assertThat(updated).isEqualTo(0);
    assertThat(runs.findById(run.getId()).getPlan()).isEqualTo(beforeAttempt.getPlan());
  }

  // The success path: while still PLANNED, the guarded write DOES apply the new plan/metrics, and
  // (unlike setPlanned) leaves status and planned_at completely untouched -- it's a narrower write by
  // design, see the mapper's javadoc.
  @Test
  void updatePlanAndMetricsSucceedsWhilePlannedAndLeavesStatusAndPlannedAtUntouched() {
    int userId = newUser("mergeRunOwner4pmOk");
    long sourceId = newProject("mergeSource4pmOk");
    long targetId = newProject("mergeTarget4pmOk");
    MergeRun run = newRunning(userId, sourceId, targetId);
    runs.setPlanned(run.getId(), EMPTY_PLAN_JSON, EMPTY_METRICS_JSON);
    var plannedAtBefore = runs.findById(run.getId()).getPlannedAt();

    String overriddenPlan = "{\"references\":[],\"names\":[{\"sourceId\":\"s1\",\"category\":\"MATCHED\","
        + "\"targetId\":\"t1\",\"score\":1.0}]}";
    String overriddenMetrics = "{\"names\":{\"new\":0,\"matched\":1,\"possibleHomonym\":0,\"possibleFuzzy\":0},"
        + "\"references\":{\"new\":0,\"matched\":0,\"possible\":0},"
        + "\"newAccepted\":0,\"newSynonyms\":0,\"unanchored\":0}";
    int updated = runs.updatePlanAndMetrics(run.getId(), overriddenPlan, overriddenMetrics);
    assertThat(updated).isEqualTo(1);

    MergeRun found = runs.findById(run.getId());
    assertThat(found.getStatus()).isEqualTo("PLANNED");
    assertThat(found.getPlannedAt()).isEqualTo(plannedAtBefore);
    assertThat(found.getPlan()).contains("MATCHED").contains("t1");
    // jsonb reformats on storage (e.g. a space after ':') -- match loosely rather than assume the
    // exact byte layout of the literal that was written, same as the plan assertion above.
    assertThat(found.getMetrics()).contains("matched").contains("1");
  }

  @Test
  void finishSetsStatusDoneAndStoresIssuesAsRetrievableJson() throws Exception {
    int userId = newUser("mergeRunOwner5");
    long sourceId = newProject("mergeSource5");
    long targetId = newProject("mergeTarget5");
    MergeRun run = newRunning(userId, sourceId, targetId);
    runs.setPlanned(run.getId(), EMPTY_PLAN_JSON, EMPTY_METRICS_JSON);
    runs.startApply(run.getId(), "FILL_GAPS", false);

    String issuesJson = "[{\"entity\":\"name\",\"sourceId\":\"s1\",\"message\":\"unanchored: Foo bar\"}]";
    int updated = runs.finish(run.getId(), issuesJson);
    assertThat(updated).isEqualTo(1);

    MergeRun found = runs.findById(run.getId());
    assertThat(found.getStatus()).isEqualTo("DONE");
    assertThat(found.getFinishedAt()).isNotNull();
    assertThat(found.getIssues()).isNotNull();

    MergeRunResponse response = MergeRunResponse.of(found, new ObjectMapper());
    assertThat(response.issues()).containsExactly(new MergeIssue("name", "s1", "unanchored: Foo bar"));
  }

  // Fix 1 in the import branch's guard (AND status <> 'DONE' on fail's UPDATE), carried over here:
  // once a run has been recorded DONE, a later fail() call -- e.g. from a post-commit
  // revalidateProject failure after apply's own finish already ran -- must be a no-op rather than
  // flipping the row back to FAILED and hiding a fully-successful merge behind an error.
  @Test
  void failDoesNotClobberAnAlreadyDoneRun() {
    int userId = newUser("mergeRunOwner6");
    long sourceId = newProject("mergeSource6");
    long targetId = newProject("mergeTarget6");
    MergeRun run = newRunning(userId, sourceId, targetId);
    runs.setPlanned(run.getId(), EMPTY_PLAN_JSON, EMPTY_METRICS_JSON);
    runs.startApply(run.getId(), "NEW_ONLY", true);

    int finished = runs.finish(run.getId(), null);
    assertThat(finished).isEqualTo(1);
    assertThat(runs.findById(run.getId()).getStatus()).isEqualTo("DONE");

    int updated = runs.fail(run.getId(), "post-commit revalidation blew up");
    assertThat(updated).isEqualTo(0);

    MergeRun found = runs.findById(run.getId());
    assertThat(found.getStatus()).isEqualTo("DONE");
    assertThat(found.getError()).isNull();
  }

  @Test
  void failSetsFailedStatusAndErrorForANonDoneRun() {
    int userId = newUser("mergeRunOwner7");
    long sourceId = newProject("mergeSource7");
    long targetId = newProject("mergeTarget7");
    MergeRun run = newRunning(userId, sourceId, targetId);

    int updated = runs.fail(run.getId(), "boom: matcher blew up");
    assertThat(updated).isEqualTo(1);

    MergeRun found = runs.findById(run.getId());
    assertThat(found.getStatus()).isEqualTo("FAILED");
    assertThat(found.getError()).isEqualTo("boom: matcher blew up");
    assertThat(found.getFinishedAt()).isNotNull();
  }

  @Test
  void findLatestByTargetReturnsTheMostRecentRunForThatTarget() {
    int userId = newUser("mergeRunOwner8");
    long sourceId = newProject("mergeSource8");
    long targetId = newProject("mergeTarget8");
    long otherTargetId = newProject("mergeTarget8other");

    // merge_run_active_idx allows at most one RUNNING/APPLYING row per target, so the first run
    // must reach a terminal status before the second can start.
    MergeRun first = newRunning(userId, sourceId, targetId);
    runs.fail(first.getId(), "superseded");
    MergeRun second = newRunning(userId, sourceId, targetId);
    newRunning(userId, sourceId, otherTargetId);

    MergeRun latest = runs.findLatestByTarget((int) targetId);
    assertThat(latest.getId()).isEqualTo(second.getId());
    assertThat(latest.getId()).isNotEqualTo(first.getId());
  }

  @Test
  void findActiveByTargetReturnsARunningOrApplyingRowAndNullOtherwise() {
    int userId = newUser("mergeRunOwner9");
    long sourceId = newProject("mergeSource9");
    long targetId = newProject("mergeTarget9");

    // Nothing yet.
    assertThat(runs.findActiveByTarget((int) targetId)).isNull();

    MergeRun run = newRunning(userId, sourceId, targetId);
    assertThat(runs.findActiveByTarget((int) targetId).getId()).isEqualTo(run.getId());

    // PLANNED does not hold the active lock (see merge_run_active_idx's comment).
    runs.setPlanned(run.getId(), EMPTY_PLAN_JSON, EMPTY_METRICS_JSON);
    assertThat(runs.findActiveByTarget((int) targetId)).isNull();

    // APPLYING holds it again.
    runs.startApply(run.getId(), "OVERWRITE", true);
    assertThat(runs.findActiveByTarget((int) targetId).getId()).isEqualTo(run.getId());

    // DONE releases it.
    runs.finish(run.getId(), null);
    assertThat(runs.findActiveByTarget((int) targetId)).isNull();
  }

  @Test
  void failStaleRunningFlipsBothRunningAndApplyingRowsToFailed() {
    int userId = newUser("mergeRunOwner10");
    long sourceId = newProject("mergeSource10");
    long targetId1 = newProject("mergeTarget10a");
    long targetId2 = newProject("mergeTarget10b");

    MergeRun runningRun = newRunning(userId, sourceId, targetId1);

    MergeRun applyingRun = newRunning(userId, sourceId, targetId2);
    runs.setPlanned(applyingRun.getId(), EMPTY_PLAN_JSON, EMPTY_METRICS_JSON);
    runs.startApply(applyingRun.getId(), "OVERWRITE", true);
    assertThat(runs.findById(applyingRun.getId()).getStatus()).isEqualTo("APPLYING");

    int reconciled = runs.failStaleRunning();
    assertThat(reconciled).isGreaterThanOrEqualTo(2);

    MergeRun foundRunning = runs.findById(runningRun.getId());
    assertThat(foundRunning.getStatus()).isEqualTo("FAILED");
    assertThat(foundRunning.getError()).isEqualTo("interrupted by restart");
    assertThat(foundRunning.getFinishedAt()).isNotNull();

    MergeRun foundApplying = runs.findById(applyingRun.getId());
    assertThat(foundApplying.getStatus()).isEqualTo("FAILED");
    assertThat(foundApplying.getError()).isEqualTo("interrupted by restart");
    assertThat(foundApplying.getFinishedAt()).isNotNull();
  }
}
