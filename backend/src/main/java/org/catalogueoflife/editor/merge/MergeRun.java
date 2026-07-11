package org.catalogueoflife.editor.merge;

import java.time.OffsetDateTime;

// One row of the `merge_run` table (see V19__merge_run.sql): the progress/result record of one
// supervised project-merge run (source project merged into an existing target project). Plain
// getter/setter POJO -- same shape/reason as ImportRun/ExportRun -- so MyBatis's reflection-based
// property access can bind it as an @Insert parameter and populate it from @Select results.
// `plan`/`metrics`/`issues` are held as raw JSON strings (JSONB columns): a TypeHandler is
// unnecessary since MergeService/MergeApplyService build the JSON once per phase and
// MergeRunResponse.of parses it for the API response.
public class MergeRun {
  private Long id;
  private Integer userId;
  private Long sourceProjectId;
  private Long targetProjectId;
  private String status;
  private String mode;
  private Boolean transactional;
  private String plan;
  private String metrics;
  private String issues;
  private OffsetDateTime startedAt;
  private OffsetDateTime plannedAt;
  private OffsetDateTime finishedAt;
  private String error;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Integer getUserId() { return userId; }
  public void setUserId(Integer userId) { this.userId = userId; }
  public Long getSourceProjectId() { return sourceProjectId; }
  public void setSourceProjectId(Long sourceProjectId) { this.sourceProjectId = sourceProjectId; }
  public Long getTargetProjectId() { return targetProjectId; }
  public void setTargetProjectId(Long targetProjectId) { this.targetProjectId = targetProjectId; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public String getMode() { return mode; }
  public void setMode(String mode) { this.mode = mode; }
  public Boolean getTransactional() { return transactional; }
  public void setTransactional(Boolean transactional) { this.transactional = transactional; }
  public String getPlan() { return plan; }
  public void setPlan(String plan) { this.plan = plan; }
  public String getMetrics() { return metrics; }
  public void setMetrics(String metrics) { this.metrics = metrics; }
  public String getIssues() { return issues; }
  public void setIssues(String issues) { this.issues = issues; }
  public OffsetDateTime getStartedAt() { return startedAt; }
  public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
  public OffsetDateTime getPlannedAt() { return plannedAt; }
  public void setPlannedAt(OffsetDateTime plannedAt) { this.plannedAt = plannedAt; }
  public OffsetDateTime getFinishedAt() { return finishedAt; }
  public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
  public String getError() { return error; }
  public void setError(String error) { this.error = error; }
}
