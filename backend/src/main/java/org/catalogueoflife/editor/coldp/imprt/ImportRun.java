package org.catalogueoflife.editor.coldp.imprt;

import java.time.OffsetDateTime;

// One row of the `import_run` table (see V18__import_run.sql): the progress/result record of one
// ColDP archive import (later tasks add the job that walks the archive and populates a new
// project). Plain getter/setter POJO -- same shape/reason as ExportRun -- so MyBatis's
// reflection-based property access can bind it as an @Insert parameter and populate it from
// @Select results. `issues` is held as the raw JSON string (a JSONB array of non-fatal per-row
// problems); a TypeHandler is unnecessary since the job builds the JSON once at finish and
// ImportRunResponse.of parses it for the API response.
public class ImportRun {
  private Long id;
  private Integer userId;
  private Long projectId;
  private String status;
  private String sourceName;
  private Boolean preserveIds;
  private String idScope;
  private Integer nameUsageCount;
  private Integer referenceCount;
  private Integer authorCount;
  private String issues;
  private OffsetDateTime startedAt;
  private OffsetDateTime finishedAt;
  private String error;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Integer getUserId() { return userId; }
  public void setUserId(Integer userId) { this.userId = userId; }
  public Long getProjectId() { return projectId; }
  public void setProjectId(Long projectId) { this.projectId = projectId; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public String getSourceName() { return sourceName; }
  public void setSourceName(String sourceName) { this.sourceName = sourceName; }
  public Boolean getPreserveIds() { return preserveIds; }
  public void setPreserveIds(Boolean preserveIds) { this.preserveIds = preserveIds; }
  public String getIdScope() { return idScope; }
  public void setIdScope(String idScope) { this.idScope = idScope; }
  public Integer getNameUsageCount() { return nameUsageCount; }
  public void setNameUsageCount(Integer nameUsageCount) { this.nameUsageCount = nameUsageCount; }
  public Integer getReferenceCount() { return referenceCount; }
  public void setReferenceCount(Integer referenceCount) { this.referenceCount = referenceCount; }
  public Integer getAuthorCount() { return authorCount; }
  public void setAuthorCount(Integer authorCount) { this.authorCount = authorCount; }
  public String getIssues() { return issues; }
  public void setIssues(String issues) { this.issues = issues; }
  public OffsetDateTime getStartedAt() { return startedAt; }
  public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
  public OffsetDateTime getFinishedAt() { return finishedAt; }
  public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
  public String getError() { return error; }
  public void setError(String error) { this.error = error; }
}
