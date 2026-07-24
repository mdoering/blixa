package org.catalogueoflife.editor.lock;

import java.util.List;
import org.catalogueoflife.editor.validation.SubtreeValidationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
  private final ApplicationEventPublisher events;

  public LockRetentionSweep(LockMapper locks, ApplicationEventPublisher events) {
    this.locks = locks;
    this.events = events;
  }

  @Scheduled(fixedDelayString = "${coldp.lock.sweep:PT5M}")
  public void sweep() {
    // Capture the objective-tagged taxon groups BEFORE deleting them, so each gets a subtree
    // revalidate (the same treatment an explicit release gives -- see LockService.release). Fetched
    // first because deleteExpired() only returns a count, not which entities it removed.
    List<LockMapper.ExpiredUsageRoot> roots = locks.findExpiredObjectiveUsageRoots();
    int n = locks.deleteExpired();
    if (n > 0) {
      log.info("Swept {} expired lock(s)", n);
    }
    // Published outside any transaction; ValidationTrigger.onSubtreeValidationEvent runs it via
    // fallbackExecution = true. Best-effort: a lock refreshed between the SELECT and the DELETE just
    // gets a harmless extra (idempotent) revalidate.
    for (LockMapper.ExpiredUsageRoot root : roots) {
      events.publishEvent(new SubtreeValidationEvent(root.projectId(), root.rootUsageId()));
    }
  }
}
