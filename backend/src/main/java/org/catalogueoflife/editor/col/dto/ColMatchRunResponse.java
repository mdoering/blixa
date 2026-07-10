package org.catalogueoflife.editor.col.dto;

import java.time.OffsetDateTime;

// The API-facing projection of one `col_match_run` row (see ColMatchRun / V12__col_match_run.sql):
// a bulk COL-match run's progress/tallies. Consumed by the (later) bulk-match status endpoint
// (Task 3); built directly off ColMatchRun rather than a dedicated MyBatis projection since every
// field is a 1:1 passthrough.
public record ColMatchRunResponse(
    Long id,
    Integer projectId,
    String status,
    Integer total,
    Integer processed,
    Integer verified,
    Integer added,
    Integer updated,
    Integer unmatched,
    OffsetDateTime startedAt,
    OffsetDateTime finishedAt,
    String error) {

  public static ColMatchRunResponse of(org.catalogueoflife.editor.col.ColMatchRun r) {
    return new ColMatchRunResponse(r.getId(), r.getProjectId(), r.getStatus(), r.getTotal(),
        r.getProcessed(), r.getVerified(), r.getAdded(), r.getUpdated(), r.getUnmatched(),
        r.getStartedAt(), r.getFinishedAt(), r.getError());
  }
}
