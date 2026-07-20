package org.catalogueoflife.editor.discussion;

import java.time.OffsetDateTime;

// A comment on a discussion (see V28__discussion_comment.sql). Per-project compound (project_id, id)
// key. `authorName` is derived from a LEFT JOIN on app_user (COALESCE(display_name, username)), not
// a column -- same as Discussion.
public class DiscussionComment {
  private Integer id;
  private Integer projectId;
  private Integer discussionId;
  private String body;
  private Integer authorId;
  private String authorOrcid;
  private String authorName; // derived (JOIN), not a column
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
  private Integer version;

  public Integer getId() { return id; }
  public void setId(Integer id) { this.id = id; }
  public Integer getProjectId() { return projectId; }
  public void setProjectId(Integer projectId) { this.projectId = projectId; }
  public Integer getDiscussionId() { return discussionId; }
  public void setDiscussionId(Integer discussionId) { this.discussionId = discussionId; }
  public String getBody() { return body; }
  public void setBody(String body) { this.body = body; }
  public Integer getAuthorId() { return authorId; }
  public void setAuthorId(Integer authorId) { this.authorId = authorId; }
  public String getAuthorOrcid() { return authorOrcid; }
  public void setAuthorOrcid(String authorOrcid) { this.authorOrcid = authorOrcid; }
  public String getAuthorName() { return authorName; }
  public void setAuthorName(String authorName) { this.authorName = authorName; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
  public Integer getVersion() { return version; }
  public void setVersion(Integer version) { this.version = version; }
}
