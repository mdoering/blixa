package org.catalogueoflife.editor.discussion.dto;

import java.time.OffsetDateTime;
import org.catalogueoflife.editor.discussion.Discussion;

public record DiscussionResponse(
    Integer id,
    Integer projectId,
    String title,
    String body,
    String status,
    String visibility,
    Integer authorId,
    String authorOrcid,
    String authorName,
    String createdVia,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Integer version,
    Mentions mentions,
    Boolean following, // whether the requesting user follows it (null on list/public rows)
    Integer followerCount) {

  // List/reverse-link rows: no resolved mentions (the body isn't rendered there), no follow state.
  public static DiscussionResponse of(Discussion d) {
    return of(d, null);
  }

  public static DiscussionResponse of(Discussion d, Mentions mentions) {
    return ofDetail(d, mentions, null, null);
  }

  // Detail view: mentions resolved from the body + the requesting user's follow state + count.
  public static DiscussionResponse ofDetail(Discussion d, Mentions mentions, Boolean following,
      Integer followerCount) {
    return new DiscussionResponse(d.getId(), d.getProjectId(), d.getTitle(), d.getBody(),
        d.getStatus(), d.getVisibility(), d.getAuthorId(), d.getAuthorOrcid(), d.getAuthorName(),
        d.getCreatedVia(), d.getCreatedAt(), d.getUpdatedAt(), d.getVersion(), mentions, following,
        followerCount);
  }
}
