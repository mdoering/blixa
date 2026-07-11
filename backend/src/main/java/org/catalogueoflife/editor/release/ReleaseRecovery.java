package org.catalogueoflife.editor.release;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// Mirrors ExportRunRecovery: ReleaseAsyncConfig.EXECUTOR_BEAN is an in-memory, per-instance thread
// pool, so a BUILDING release row cannot survive the JVM that started it. On every startup, once the
// application context is fully up, sweep any leftover BUILDING row (only possible as an orphan from
// a previous instance) to FAILED so it reads as a clean, terminal result instead of a stuck spinner.
@Component
public class ReleaseRecovery {
  private static final Logger log = LoggerFactory.getLogger(ReleaseRecovery.class);
  private final ReleaseMapper releases;

  public ReleaseRecovery(ReleaseMapper releases) { this.releases = releases; }

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    int n = releases.failStaleBuilding();
    if (n > 0) log.warn("Reconciled {} release row(s) left BUILDING by a previous instance as FAILED", n);
    else log.info("No stale BUILDING release rows found at startup");
  }
}
