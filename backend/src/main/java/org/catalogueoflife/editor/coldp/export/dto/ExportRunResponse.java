package org.catalogueoflife.editor.coldp.export.dto;

import java.time.OffsetDateTime;
import org.catalogueoflife.editor.coldp.export.ExportRun;

// The API-facing projection of one `export_run` row (see ExportRun / V15__export_run.sql): an
// export run's status/result. Deliberately omits filePath -- that's an internal server-side path
// (ExportRunService.run's target under coldp.export.dir), never something the API should leak;
// fileName is the friendly download name used by the .../file endpoint's Content-Disposition.
public record ExportRunResponse(
    Long id,
    Integer projectId,
    String status,
    String fileName,
    Long fileSize,
    Integer nameUsageCount,
    Integer referenceCount,
    OffsetDateTime startedAt,
    OffsetDateTime finishedAt,
    String error) {

  public static ExportRunResponse of(ExportRun r) {
    return new ExportRunResponse(r.getId(), r.getProjectId(), r.getStatus(), r.getFileName(),
        r.getFileSize(), r.getNameUsageCount(), r.getReferenceCount(), r.getStartedAt(),
        r.getFinishedAt(), r.getError());
  }
}
