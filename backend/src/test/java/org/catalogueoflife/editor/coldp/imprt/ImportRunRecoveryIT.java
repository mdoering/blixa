package org.catalogueoflife.editor.coldp.imprt;

import static org.assertj.core.api.Assertions.assertThat;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// ImportRunMapper.failStaleRunning / ImportRunRecovery: the startup sweep that reconciles any
// import_run row left RUNNING by a killed instance (blue-green redeploy mid-run) as FAILED -- see
// ImportRunRecovery's class comment for the full reasoning (the import job's executor is
// in-memory/per-instance, so a RUNNING row can only be an orphan from a previous instance).
// Mirrors ColMatchRunRecoveryIT's shape exactly: exercises the mapper method directly first (also
// proving a DONE row is left untouched by the bulk sweep), then separately drives the same sweep
// through the recovery component itself, since the real @EventListener only fires once per
// application context -- this test needs a row inserted AFTER that startup event to prove the
// sweep logic actually works, not just that it ran once already against an empty table.
class ImportRunRecoveryIT extends AbstractPostgresIT {

  @Autowired AppUserMapper users;
  @Autowired ImportRunMapper runs;
  @Autowired ImportRunRecovery recovery;

  private int createUser(String username) {
    AppUser u = new AppUser();
    u.setUsername(username);
    users.insert(u);
    return u.getId();
  }

  private long insertRunning(int userId, String sourceName) {
    ImportRun run = new ImportRun();
    run.setUserId(userId);
    run.setSourceName(sourceName);
    run.setPreserveIds(false);
    runs.insertRunning(run);
    return run.getId();
  }

  @Test
  void failStaleRunningMarksRunningRowsFailedAndLeavesDoneRowsAlone() {
    int userId = createUser("importRecoveryOwner1");

    // Finish the first row before inserting the second, purely for readability -- import_run has
    // no "one RUNNING row at a time" partial-unique index (see V18__import_run.sql's own comment:
    // ImportAsyncConfig's single-thread executor already serializes runs), so this ordering isn't
    // load-bearing, only the end state (one DONE, one left RUNNING/stale) matters below.
    long doneRunId = insertRunning(userId, "done.zip");
    runs.finish(doneRunId, 1, 0, 0, null);
    long staleRunId = insertRunning(userId, "stale.zip");

    int reconciled = runs.failStaleRunning();
    assertThat(reconciled).isGreaterThanOrEqualTo(1);

    ImportRun stale = runs.findById(staleRunId);
    assertThat(stale.getStatus()).isEqualTo("FAILED");
    assertThat(stale.getError()).isEqualTo("interrupted by restart");
    assertThat(stale.getFinishedAt()).isNotNull();

    // A DONE row must be left untouched by the sweep.
    ImportRun done = runs.findById(doneRunId);
    assertThat(done.getStatus()).isEqualTo("DONE");
    assertThat(done.getError()).isNull();
  }

  @Test
  void recoveryComponentReconcilesAStaleRunningRow() {
    int userId = createUser("importRecoveryOwner2");
    long staleRunId = insertRunning(userId, "component-stale.zip");

    // Invoke the same handler ApplicationReadyEvent would fire -- the real startup event already
    // ran once (against whatever rows existed at context boot) before this test's row even
    // existed, so calling the handler directly is the only way to exercise it against a row
    // inserted mid-test (mirrors ColMatchRunRecoveryIT's identical reasoning).
    recovery.onApplicationReady();

    ImportRun stale = runs.findById(staleRunId);
    assertThat(stale.getStatus()).isEqualTo("FAILED");
    assertThat(stale.getError()).isEqualTo("interrupted by restart");
    assertThat(stale.getFinishedAt()).isNotNull();
  }
}
