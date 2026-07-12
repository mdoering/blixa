package org.catalogueoflife.editor.join;

import java.time.OffsetDateTime;

public class JoinRequest {
  private Integer id;
  private Integer projectId;
  private String orcid;
  private String name;
  private String message;
  private OffsetDateTime createdAt;

  public JoinRequest() {}

  public JoinRequest(Integer projectId, String orcid, String name, String message) {
    this.projectId = projectId;
    this.orcid = orcid;
    this.name = name;
    this.message = message;
  }

  public Integer getId() { return id; }
  public void setId(Integer id) { this.id = id; }
  public Integer getProjectId() { return projectId; }
  public void setProjectId(Integer projectId) { this.projectId = projectId; }
  public String getOrcid() { return orcid; }
  public void setOrcid(String orcid) { this.orcid = orcid; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getMessage() { return message; }
  public void setMessage(String message) { this.message = message; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
