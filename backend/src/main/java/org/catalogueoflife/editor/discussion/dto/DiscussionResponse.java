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
    Mentions mentions) {

  // List/reverse-link rows: no resolved mentions (the body isn't rendered there).
  public static DiscussionResponse of(Discussion d) {
    return of(d, null);
  }

  // Detail view: mentions resolved from the body (#nameID / @orcid).
  public static DiscussionResponse of(Discussion d, Mentions mentions) {
    return new DiscussionResponse(d.getId(), d.getProjectId(), d.getTitle(), d.getBody(),
        d.getStatus(), d.getVisibility(), d.getAuthorId(), d.getAuthorOrcid(), d.getAuthorName(),
        d.getCreatedVia(), d.getCreatedAt(), d.getUpdatedAt(), d.getVersion(), mentions);
  }
}
