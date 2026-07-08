package org.catalogueoflife.editor.task;

import java.time.OffsetDateTime;

// One row of the `task` table (see V6__task.sql): a titled unit of work ("work-session") a user
// undertakes -- explains lock intent (Task 3) and groups changelog entries (Task 2). Plain
// getter/setter POJO (like Lock/Change) so MyBatis's reflection-based property access can bind it
// as an @Insert parameter and populate it from @Select results.
public class Task {
  private Integer id;
  private Integer projectId;
  private Integer userId;
  private String title;
  private String description;
  private String status;
  private OffsetDateTime createdAt;
  private OffsetDateTime closedAt;

  public Integer getId() { return id; }
  public void setId(Integer id) { this.id = id; }
  public Integer getProjectId() { return projectId; }
  public void setProjectId(Integer projectId) { this.projectId = projectId; }
  public Integer getUserId() { return userId; }
  public void setUserId(Integer userId) { this.userId = userId; }
  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }
  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
  public OffsetDateTime getClosedAt() { return closedAt; }
  public void setClosedAt(OffsetDateTime closedAt) { this.closedAt = closedAt; }
}
