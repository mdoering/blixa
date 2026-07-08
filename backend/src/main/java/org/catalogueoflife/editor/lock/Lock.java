package org.catalogueoflife.editor.lock;

import java.time.OffsetDateTime;

// One row of the `lock` table (see V5__lock.sql): an advisory, auto-expiring claim that a given
// user is working on a given entity. At most one row exists per (projectId, entityType,
// entityId) -- see LockMapper.upsertTakeover for how a stale (expired) or self-held lock is taken
// over in place rather than producing a second row. A lock with expires_at in the past is treated
// as absent by every read path (findActive filters it out; upsertTakeover treats it as free).
// Plain getter/setter POJO (like Change) so MyBatis's reflection-based property access can bind
// it as a @Select result.
public class Lock {
  private Integer id;
  private Integer projectId;
  private String entityType;
  private Integer entityId;
  private Integer userId;
  private String username;
  private OffsetDateTime acquiredAt;
  private OffsetDateTime expiresAt;
  private Integer taskId;
  private String taskTitle;

  public Integer getId() { return id; }
  public void setId(Integer id) { this.id = id; }
  public Integer getProjectId() { return projectId; }
  public void setProjectId(Integer projectId) { this.projectId = projectId; }
  public String getEntityType() { return entityType; }
  public void setEntityType(String entityType) { this.entityType = entityType; }
  public Integer getEntityId() { return entityId; }
  public void setEntityId(Integer entityId) { this.entityId = entityId; }
  public Integer getUserId() { return userId; }
  public void setUserId(Integer userId) { this.userId = userId; }
  public String getUsername() { return username; }
  public void setUsername(String username) { this.username = username; }
  public OffsetDateTime getAcquiredAt() { return acquiredAt; }
  public void setAcquiredAt(OffsetDateTime acquiredAt) { this.acquiredAt = acquiredAt; }
  public OffsetDateTime getExpiresAt() { return expiresAt; }
  public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
  public Integer getTaskId() { return taskId; }
  public void setTaskId(Integer taskId) { this.taskId = taskId; }
  public String getTaskTitle() { return taskTitle; }
  public void setTaskTitle(String taskTitle) { this.taskTitle = taskTitle; }
}
