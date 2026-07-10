package org.catalogueoflife.editor.col;

import static org.assertj.core.api.Assertions.assertThat;

import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectMapper;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.gbif.nameparser.api.NomCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// ColMatchRunMapper.failStaleRunning / ColMatchRunRecovery: the startup sweep that reconciles any
// col_match_run row left RUNNING by a killed instance (blue-green redeploy mid-run) as FAILED --
// see ColMatchRunRecovery's class comment for the full reasoning. Exercises the mapper method
// directly (like ColMatchJobIT), and separately drives it through the recovery component itself,
// since the real @EventListener only fires once per application context and this test needs a
// row inserted AFTER that startup event to prove the sweep logic works.
class ColMatchRunRecoveryIT extends AbstractPostgresIT {

  @Autowired ProjectMapper projects;
  @Autowired ColMatchRunMapper runs;
  @Autowired ColMatchRunRecovery recovery;

  private int createProject(String title) {
    Project p = new Project();
    p.setTitle(title);
    p.setNomCode(NomCode.ZOOLOGICAL);
    projects.insert(p);
    return p.getId();
  }

  private long insertRunning(int projectId) {
    ColMatchRun run = new ColMatchRun();
    run.setProjectId(projectId);
    runs.insertRunning(run);
    return run.getId();
  }

  @Test
  void failStaleRunningMarksRunningRowsFailedAndLeavesDoneRowsAlone() {
    int pid = createProject("col-match-recovery");

    // Finish the first row before inserting the second: col_match_run_active_idx (V13) allows only
    // one RUNNING row per project at a time, so both rows can never be RUNNING simultaneously here
    // -- order doesn't matter to the assertions below, only that one ends up DONE and the other
    // stays RUNNING (stale) for the sweep to find.
    long doneRunId = insertRunning(pid);
    runs.finish(doneRunId);
    long staleRunId = insertRunning(pid);

    int reconciled = runs.failStaleRunning();
    assertThat(reconciled).isGreaterThanOrEqualTo(1);

    ColMatchRun stale = runs.findById(staleRunId);
    assertThat(stale.getStatus()).isEqualTo("FAILED");
    assertThat(stale.getError()).isEqualTo("interrupted by restart");
    assertThat(stale.getFinishedAt()).isNotNull();

    // A DONE row must be left untouched by the sweep.
    ColMatchRun done = runs.findById(doneRunId);
    assertThat(done.getStatus()).isEqualTo("DONE");
    assertThat(done.getError()).isNull();
  }

  @Test
  void recoveryComponentReconcilesAStaleRunningRow() {
    int pid = createProject("col-match-recovery-component");
    long staleRunId = insertRunning(pid);

    // Invoke the same handler ApplicationReadyEvent would fire -- the real startup event already
    // ran once (against whatever rows existed at context boot) before this test's row even
    // existed, so calling the handler directly is the only way to exercise it against a row
    // inserted mid-test, exactly like ColMatchJobIT calls service.runSync directly rather than
    // relying on the async trigger.
    recovery.onApplicationReady();

    ColMatchRun stale = runs.findById(staleRunId);
    assertThat(stale.getStatus()).isEqualTo("FAILED");
    assertThat(stale.getError()).isEqualTo("interrupted by restart");
    assertThat(stale.getFinishedAt()).isNotNull();
  }
}
