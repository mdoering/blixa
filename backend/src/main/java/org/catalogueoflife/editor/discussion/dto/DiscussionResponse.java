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
    Integer version) {

  public static DiscussionResponse of(Discussion d) {
    return new DiscussionResponse(d.getId(), d.getProjectId(), d.getTitle(), d.getBody(),
        d.getStatus(), d.getVisibility(), d.getAuthorId(), d.getAuthorOrcid(), d.getAuthorName(),
        d.getCreatedVia(), d.getCreatedAt(), d.getUpdatedAt(), d.getVersion());
  }
}
