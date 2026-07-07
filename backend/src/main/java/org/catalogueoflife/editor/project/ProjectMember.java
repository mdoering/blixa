package org.catalogueoflife.editor.project;

public class ProjectMember {
  private Long projectId;
  private Long userId;
  private String role;

  public ProjectMember() {}

  public ProjectMember(Long projectId, Long userId, String role) {
    this.projectId = projectId;
    this.userId = userId;
    this.role = role;
  }

  public Long getProjectId() { return projectId; }
  public void setProjectId(Long projectId) { this.projectId = projectId; }
  public Long getUserId() { return userId; }
  public void setUserId(Long userId) { this.userId = userId; }
  public String getRole() { return role; }
  public void setRole(String role) { this.role = role; }
}
