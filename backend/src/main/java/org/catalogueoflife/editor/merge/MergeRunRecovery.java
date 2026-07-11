package org.catalogueoflife.editor.merge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// Assumption this class exists to enforce: the merge job's executor
// (MergeAsyncConfig.EXECUTOR_BEAN) is an in-memory, per-instance thread pool, so a running
// merge_run CANNOT survive the JVM that started it -- there is no persistence, queue, or other
// process able to resume, finish, or fail it once that instance is gone. This project deploys
// blue-green (a redeploy starts a fresh instance and kills the old one), so a restart mid-run
// abandons its RUNNING (compute-plan) or APPLYING (apply) row for good: nothing left to ever move
// it to DONE/FAILED, and its /merge/{runId} poll target would hang forever. On every startup, once
// the application context is fully up (ApplicationReadyEvent), this sweeps any such leftover
// RUNNING/APPLYING row -- which can only be an orphan from a previous instance, since the merge job
// only inserts a RUNNING row (or moves it to APPLYING) in response to a request, and no request can
// reach this instance before it's ready -- and marks it FAILED so it reads as a clean, terminal (if
// inaccurate as to cause) result instead of a stuck spinner. Mirrors ImportRunRecovery exactly.
@Component
public class MergeRunRecovery {

  private static final Logger log = LoggerFactory.getLogger(MergeRunRecovery.class);

  private final MergeRunMapper runs;

  public MergeRunRecovery(MergeRunMapper runs) {
    this.runs = runs;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    int reconciled = runs.failStaleRunning();
    if (reconciled > 0) {
      log.warn("Reconciled {} merge_run row(s) left RUNNING/APPLYING by a previous instance as FAILED", reconciled);
    } else {
      log.info("No stale RUNNING/APPLYING merge_run rows found at startup");
    }
  }
}
