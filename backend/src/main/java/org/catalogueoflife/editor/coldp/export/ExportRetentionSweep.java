package org.catalogueoflife.editor.coldp.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Periodic disk + row cleanup for finished exports: a DONE export_run's zip sits on disk
// (coldp.export.dir) until this sweeps it away, coldp.export.ttl (default P7D) after it finished --
// exports are meant to be downloaded promptly, not accumulate forever. Runs hourly (a fixed,
// hardcoded cadence -- ttl is the only tunable the brief calls for); ExportAsyncConfig's
// @EnableScheduling makes @Scheduled actually fire.
@Component
public class ExportRetentionSweep {

  private static final Logger log = LoggerFactory.getLogger(ExportRetentionSweep.class);
  private static final String SWEEP_INTERVAL_MS = "3600000"; // 1 hour

  private final ExportRunMapper runs;
  private final Duration ttl;

  public ExportRetentionSweep(ExportRunMapper runs, @Value("${coldp.export.ttl:P7D}") String ttl) {
    this.runs = runs;
    this.ttl = Duration.parse(ttl);
  }

  @Scheduled(fixedDelayString = SWEEP_INTERVAL_MS)
  public void sweep() {
    OffsetDateTime cutoff = OffsetDateTime.now().minus(ttl);
    List<ExportRun> stale = runs.findTerminalFinishedBefore(cutoff);
    for (ExportRun r : stale) {
      if (r.getFilePath() != null) {
        try {
          Files.deleteIfExists(Path.of(r.getFilePath()));
        } catch (IOException e) {
          log.warn("failed to delete export file {} for export_run {}: {}",
              r.getFilePath(), r.getId(), e.getMessage());
        }
      }
      runs.deleteById(r.getId());
    }
    if (!stale.isEmpty()) {
      log.info("export retention sweep removed {} export_run row(s) older than {}", stale.size(), ttl);
    }
  }
}
