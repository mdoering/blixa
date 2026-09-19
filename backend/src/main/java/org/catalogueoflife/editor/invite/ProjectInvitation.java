package org.catalogueoflife.editor.invite;

import java.time.OffsetDateTime;

public class ProjectInvitation {
  private Integer id;
  private Integer projectId;
  private String email;
  private String role;
  private String message;
  private String token;
  private Integer invitedBy;
  private OffsetDateTime createdAt;
  private OffsetDateTime expiresAt;
  private OffsetDateTime acceptedAt;
  private Integer acceptedBy;
  // Read-only, joined in by InvitationMapper's SELECTs (project.title; inviter display name or username).
  private String projectTitle;
  private String invitedByName;

  public boolean isExpired() {
    return expiresAt != null && expiresAt.isBefore(OffsetDateTime.now());
  }

  public Integer getId() { return id; }
  public void setId(Integer id) { this.id = id; }
  public Integer getProjectId() { return projectId; }
  public void setProjectId(Integer projectId) { this.projectId = projectId; }
  public String getEmail() { return email; }
  public void setEmail(String email) { this.email = email; }
  public String getRole() { return role; }
  public void setRole(String role) { this.role = role; }
  public String getMessage() { return message; }
  public void setMessage(String message) { this.message = message; }
  public String getToken() { return token; }
  public void setToken(String token) { this.token = token; }
  public Integer getInvitedBy() { return invitedBy; }
  public void setInvitedBy(Integer invitedBy) { this.invitedBy = invitedBy; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
  public OffsetDateTime getExpiresAt() { return expiresAt; }
  public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
  public OffsetDateTime getAcceptedAt() { return acceptedAt; }
  public void setAcceptedAt(OffsetDateTime acceptedAt) { this.acceptedAt = acceptedAt; }
  public Integer getAcceptedBy() { return acceptedBy; }
  public void setAcceptedBy(Integer acceptedBy) { this.acceptedBy = acceptedBy; }
  public String getProjectTitle() { return projectTitle; }
  public void setProjectTitle(String projectTitle) { this.projectTitle = projectTitle; }
  public String getInvitedByName() { return invitedByName; }
  public void setInvitedByName(String invitedByName) { this.invitedByName = invitedByName; }
}
