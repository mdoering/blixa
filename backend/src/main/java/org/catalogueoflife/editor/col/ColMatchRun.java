package org.catalogueoflife.editor.col;

import java.time.OffsetDateTime;

// One row of the `col_match_run` table (see V12__col_match_run.sql): the progress/tally record of
// one project-wide bulk COL-match run (ColMatchJobService.runSync). Plain getter/setter POJO (like
// Task/Change/Issue) so MyBatis's reflection-based property access can bind it as an @Insert
// parameter and populate it from @Select results.
public class ColMatchRun {
  private Long id;
  private Integer projectId;
  private String status;
  private Integer total;
  private Integer processed;
  private Integer verified;
  private Integer added;
  private Integer updated;
  private Integer unmatched;
  private OffsetDateTime startedAt;
  private OffsetDateTime finishedAt;
  private String error;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Integer getProjectId() { return projectId; }
  public void setProjectId(Integer projectId) { this.projectId = projectId; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public Integer getTotal() { return total; }
  public void setTotal(Integer total) { this.total = total; }
  public Integer getProcessed() { return processed; }
  public void setProcessed(Integer processed) { this.processed = processed; }
  public Integer getVerified() { return verified; }
  public void setVerified(Integer verified) { this.verified = verified; }
  public Integer getAdded() { return added; }
  public void setAdded(Integer added) { this.added = added; }
  public Integer getUpdated() { return updated; }
  public void setUpdated(Integer updated) { this.updated = updated; }
  public Integer getUnmatched() { return unmatched; }
  public void setUnmatched(Integer unmatched) { this.unmatched = unmatched; }
  public OffsetDateTime getStartedAt() { return startedAt; }
  public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
  public OffsetDateTime getFinishedAt() { return finishedAt; }
  public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
  public String getError() { return error; }
  public void setError(String error) { this.error = error; }
}
