package org.catalogueoflife.editor.col;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// Assumption this class exists to enforce: the bulk COL-match job's executor
// (ColMatchAsyncConfig.EXECUTOR_BEAN) is an in-memory, per-instance thread pool, so a running
// col_match_run CANNOT survive the JVM that started it -- there is no persistence, queue, or other
// process able to resume, finish, or fail it once that instance is gone. This project deploys
// blue-green (a redeploy starts a fresh instance and kills the old one), so a restart mid-run
// abandons its RUNNING row for good: nothing left to ever move it to DONE/FAILED, and its
// /col-match/{runId} poll target would hang forever. On every startup, once the application
// context is fully up (ApplicationReadyEvent), this sweeps any such leftover RUNNING row -- which
// can only be an orphan from a previous instance, since ColMatchJobService.start() only inserts a
// RUNNING row in response to a request, and no request can reach this instance before it's ready
// -- and marks it FAILED so it reads as a clean, terminal (if inaccurate as to cause) result
// instead of a stuck spinner.
@Component
public class ColMatchRunRecovery {

  private static final Logger log = LoggerFactory.getLogger(ColMatchRunRecovery.class);

  private final ColMatchRunMapper runs;

  public ColMatchRunRecovery(ColMatchRunMapper runs) {
    this.runs = runs;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    int reconciled = runs.failStaleRunning();
    if (reconciled > 0) {
      log.warn("Reconciled {} col_match_run row(s) left RUNNING by a previous instance as FAILED", reconciled);
    } else {
      log.info("No stale RUNNING col_match_run rows found at startup");
    }
  }
}
