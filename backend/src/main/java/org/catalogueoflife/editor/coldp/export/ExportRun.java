package org.catalogueoflife.editor.coldp.export;

import java.time.OffsetDateTime;

// One row of the `export_run` table (see V15__export_run.sql): the progress/result record of one
// project-wide ColDP export run (ExportRunService.run -> ColdpWriter.write). Plain getter/setter
// POJO (like ColMatchRun/Task/Change/Issue) so MyBatis's reflection-based property access can bind
// it as an @Insert parameter and populate it from @Select results.
public class ExportRun {
  private Long id;
  private Integer projectId;
  private String status;
  private String filePath;
  private String fileName;
  private Long fileSize;
  private Integer nameUsageCount;
  private Integer referenceCount;
  private OffsetDateTime startedAt;
  private OffsetDateTime finishedAt;
  private String error;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Integer getProjectId() { return projectId; }
  public void setProjectId(Integer projectId) { this.projectId = projectId; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public String getFilePath() { return filePath; }
  public void setFilePath(String filePath) { this.filePath = filePath; }
  public String getFileName() { return fileName; }
  public void setFileName(String fileName) { this.fileName = fileName; }
  public Long getFileSize() { return fileSize; }
  public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
  public Integer getNameUsageCount() { return nameUsageCount; }
  public void setNameUsageCount(Integer nameUsageCount) { this.nameUsageCount = nameUsageCount; }
  public Integer getReferenceCount() { return referenceCount; }
  public void setReferenceCount(Integer referenceCount) { this.referenceCount = referenceCount; }
  public OffsetDateTime getStartedAt() { return startedAt; }
  public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
  public OffsetDateTime getFinishedAt() { return finishedAt; }
  public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
  public String getError() { return error; }
  public void setError(String error) { this.error = error; }
}
