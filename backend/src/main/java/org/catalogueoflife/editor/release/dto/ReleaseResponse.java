package org.catalogueoflife.editor.release.dto;

import java.time.OffsetDateTime;
import org.catalogueoflife.editor.release.Release;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Editor-facing release. `metrics` is embedded as parsed JSON (not a quoted string) so the UI reads
// it directly; filePath is intentionally omitted.
public record ReleaseResponse(
    Integer id, Integer projectId, String version, String notes, String status,
    Integer nameUsageCount, JsonNode metrics, String fileName, Long fileSize,
    String error, OffsetDateTime createdAt) {

  private static final ObjectMapper JSON = new ObjectMapper();

  public static ReleaseResponse of(Release r) {
    JsonNode m = null;
    if (r.getMetrics() != null && !r.getMetrics().isBlank()) {
      try { m = JSON.readTree(r.getMetrics()); } catch (Exception ignored) { }
    }
    return new ReleaseResponse(r.getId(), r.getProjectId(), r.getVersion(), r.getNotes(),
        r.getStatus(), r.getNameUsageCount(), m, r.getFileName(), r.getFileSize(),
        r.getError(), r.getCreatedAt());
  }
}
