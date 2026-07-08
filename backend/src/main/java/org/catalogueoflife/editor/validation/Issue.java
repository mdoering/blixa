package org.catalogueoflife.editor.validation;

import java.time.OffsetDateTime;

// One row of the `issue` table (see V7__issue.sql): a single rule's finding attached to an entity,
// carrying the reviewer lifecycle (see IssueStatus). Uses a global-identity Integer id + project_id
// (sibling of change/lock/task, NOT the domain entities' compound (project_id, id) key) -- an issue
// references its subject via entityType/entityId instead. Plain getter/setter POJO (like
// Task/Change) so MyBatis's reflection-based property access can bind it as an @Insert parameter
// and populate it from @Select results; `context` is the JSONB column's raw JSON text (same
// pattern as Change.diff/Project.metadata), `severity`/`status` are Severity/IssueStatus's name().
public class Issue {
  private Integer id;
  private Integer projectId;
  private String entityType;
  private Integer entityId;
  private String rule;
  private String severity;
  private String message;
  private String context;
  private String status;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
  private Integer reviewerId;
  private OffsetDateTime reviewedAt;

  public Integer getId() { return id; }
  public void setId(Integer id) { this.id = id; }
  public Integer getProjectId() { return projectId; }
  public void setProjectId(Integer projectId) { this.projectId = projectId; }
  public String getEntityType() { return entityType; }
  public void setEntityType(String entityType) { this.entityType = entityType; }
  public Integer getEntityId() { return entityId; }
  public void setEntityId(Integer entityId) { this.entityId = entityId; }
  public String getRule() { return rule; }
  public void setRule(String rule) { this.rule = rule; }
  public String getSeverity() { return severity; }
  public void setSeverity(String severity) { this.severity = severity; }
  public String getMessage() { return message; }
  public void setMessage(String message) { this.message = message; }
  public String getContext() { return context; }
  public void setContext(String context) { this.context = context; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
  public Integer getReviewerId() { return reviewerId; }
  public void setReviewerId(Integer reviewerId) { this.reviewerId = reviewerId; }
  public OffsetDateTime getReviewedAt() { return reviewedAt; }
  public void setReviewedAt(OffsetDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
}
