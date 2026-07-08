package org.catalogueoflife.editor.audit;

import java.time.OffsetDateTime;

// One immutable row of the append-only change/audit log (see V4__change.sql): who did what to
// which entity, when, and (for updates) which fields changed. Written exactly once via
// AuditService.record and never updated/deleted -- kept as a plain getter/setter POJO (like
// Project/Reference/NameUsage) rather than a record purely so MyBatis's reflection-based property
// access can both bind it as an @Insert parameter and populate it from @Select results.
//
// `diff` is the JSONB column's raw JSON text (see V4__change.sql): CREATE -> {"after": {...}},
// DELETE -> {"before": {...}}, UPDATE -> {"field": {"from": ..., "to": ...}, ...}. Callers of the
// read endpoint parse it as JSON themselves -- see AuditService for how it's produced.
public class Change {
  private Integer id;
  private Integer projectId;
  private Integer userId;
  private String username;
  private OffsetDateTime at;
  private String entityType;
  private Integer entityId;
  private String operation;
  private String diff;

  public Integer getId() { return id; }
  public void setId(Integer id) { this.id = id; }
  public Integer getProjectId() { return projectId; }
  public void setProjectId(Integer projectId) { this.projectId = projectId; }
  public Integer getUserId() { return userId; }
  public void setUserId(Integer userId) { this.userId = userId; }
  public String getUsername() { return username; }
  public void setUsername(String username) { this.username = username; }
  public OffsetDateTime getAt() { return at; }
  public void setAt(OffsetDateTime at) { this.at = at; }
  public String getEntityType() { return entityType; }
  public void setEntityType(String entityType) { this.entityType = entityType; }
  public Integer getEntityId() { return entityId; }
  public void setEntityId(Integer entityId) { this.entityId = entityId; }
  public String getOperation() { return operation; }
  public void setOperation(String operation) { this.operation = operation; }
  public String getDiff() { return diff; }
  public void setDiff(String diff) { this.diff = diff; }
}
