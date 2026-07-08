package org.catalogueoflife.editor.project;

public class ProjectMember {
  private Integer projectId;
  private Integer userId;
  private String role;

  public ProjectMember() {}

  public ProjectMember(Integer projectId, Integer userId, String role) {
    this.projectId = projectId;
    this.userId = userId;
    this.role = role;
  }

  public Integer getProjectId() { return projectId; }
  public void setProjectId(Integer projectId) { this.projectId = projectId; }
  public Integer getUserId() { return userId; }
  public void setUserId(Integer userId) { this.userId = userId; }
  public String getRole() { return role; }
  public void setRole(String role) { this.role = role; }
}
