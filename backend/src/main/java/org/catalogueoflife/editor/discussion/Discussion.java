package org.catalogueoflife.editor.discussion;

import java.time.OffsetDateTime;

// A discussion thread within a project (see V27__discussion.sql). Uses a per-project compound
// (project_id, id) key: id alone is only meaningful together with projectId. `authorName` is not a
// column -- it is filled from a LEFT JOIN on app_user (COALESCE(display_name, username)) in the
// mapper's SELECTs so the UI can label the author without an extra round-trip. status/visibility are
// carried as the enum names (see DiscussionStatus/DiscussionVisibility), validated in the service.
public class Discussion {
  private Integer id;
  private Integer projectId;
  private String title;
  private String body;
  private String status;
  private String visibility;
  private Integer authorId;
  private String authorOrcid;
  private String authorName; // derived (JOIN), not a column
  private String createdVia;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
  private Integer version;

  public Integer getId() { return id; }
  public void setId(Integer id) { this.id = id; }
  public Integer getProjectId() { return projectId; }
  public void setProjectId(Integer projectId) { this.projectId = projectId; }
  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }
  public String getBody() { return body; }
  public void setBody(String body) { this.body = body; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public String getVisibility() { return visibility; }
  public void setVisibility(String visibility) { this.visibility = visibility; }
  public Integer getAuthorId() { return authorId; }
  public void setAuthorId(Integer authorId) { this.authorId = authorId; }
  public String getAuthorOrcid() { return authorOrcid; }
  public void setAuthorOrcid(String authorOrcid) { this.authorOrcid = authorOrcid; }
  public String getAuthorName() { return authorName; }
  public void setAuthorName(String authorName) { this.authorName = authorName; }
  public String getCreatedVia() { return createdVia; }
  public void setCreatedVia(String createdVia) { this.createdVia = createdVia; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
  public Integer getVersion() { return version; }
  public void setVersion(Integer version) { this.version = version; }
}
