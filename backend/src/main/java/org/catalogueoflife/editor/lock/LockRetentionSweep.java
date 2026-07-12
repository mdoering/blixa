package org.catalogueoflife.editor.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Periodic cleanup of expired advisory locks: every read path (findActive, upsertTakeover)
// already treats a lapsed expires_at as "absent", but the row otherwise sits in the table
// forever -- this physically removes it. coldp.lock.sweep (default PT5M) is the only tunable;
// mirrors ExportRetentionSweep's structure. ExportAsyncConfig's @EnableScheduling (app-wide)
// makes @Scheduled actually fire, so it is not repeated here.
@Component
public class LockRetentionSweep {

  private static final Logger log = LoggerFactory.getLogger(LockRetentionSweep.class);

  private final LockMapper locks;

  public LockRetentionSweep(LockMapper locks) {
    this.locks = locks;
  }

  @Scheduled(fixedDelayString = "${coldp.lock.sweep:PT5M}")
  public void sweep() {
    int n = locks.deleteExpired();
    if (n > 0) {
      log.info("Swept {} expired lock(s)", n);
    }
  }
}
