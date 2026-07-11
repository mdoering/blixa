package org.catalogueoflife.editor.release;

import java.time.OffsetDateTime;

public class Release {
  private Integer id;
  private Integer projectId;
  private String version;
  private String notes;
  private String status;            // BUILDING | READY | FAILED
  private Integer nameUsageCount;
  private String metrics;           // JSON string (jsonb)
  private String filePath;
  private String fileName;
  private Long fileSize;
  private String error;
  private Integer createdBy;
  private OffsetDateTime createdAt;

  public Integer getId() { return id; }
  public void setId(Integer id) { this.id = id; }
  public Integer getProjectId() { return projectId; }
  public void setProjectId(Integer projectId) { this.projectId = projectId; }
  public String getVersion() { return version; }
  public void setVersion(String version) { this.version = version; }
  public String getNotes() { return notes; }
  public void setNotes(String notes) { this.notes = notes; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public Integer getNameUsageCount() { return nameUsageCount; }
  public void setNameUsageCount(Integer nameUsageCount) { this.nameUsageCount = nameUsageCount; }
  public String getMetrics() { return metrics; }
  public void setMetrics(String metrics) { this.metrics = metrics; }
  public String getFilePath() { return filePath; }
  public void setFilePath(String filePath) { this.filePath = filePath; }
  public String getFileName() { return fileName; }
  public void setFileName(String fileName) { this.fileName = fileName; }
  public Long getFileSize() { return fileSize; }
  public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
  public String getError() { return error; }
  public void setError(String error) { this.error = error; }
  public Integer getCreatedBy() { return createdBy; }
  public void setCreatedBy(Integer createdBy) { this.createdBy = createdBy; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
