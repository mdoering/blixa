package org.catalogueoflife.editor.discussion.dto;

import java.time.OffsetDateTime;
import org.catalogueoflife.editor.discussion.DiscussionComment;

public record CommentResponse(
    Integer id,
    Integer projectId,
    Integer discussionId,
    String body,
    Integer authorId,
    String authorOrcid,
    String authorName,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Integer version,
    Mentions mentions) {

  public static CommentResponse of(DiscussionComment c, Mentions mentions) {
    return new CommentResponse(c.getId(), c.getProjectId(), c.getDiscussionId(), c.getBody(),
        c.getAuthorId(), c.getAuthorOrcid(), c.getAuthorName(), c.getCreatedAt(), c.getUpdatedAt(),
        c.getVersion(), mentions);
  }
}
