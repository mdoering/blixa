package org.catalogueoflife.editor.name.dto;

import org.catalogueoflife.editor.name.Reference;

public record ReferenceResponse(
    Integer id,
    String citation,
    String type,
    String author,
    String editor,
    String title,
    String containerTitle,
    String issued,
    String volume,
    String issue,
    String page,
    String publisher,
    String doi,
    String isbn,
    String issn,
    String link,
    String remarks,
    Integer version) {

  public static ReferenceResponse of(Reference r) {
    return new ReferenceResponse(r.getId(), r.getCitation(), r.getType(), r.getAuthor(),
        r.getEditor(), r.getTitle(), r.getContainerTitle(), r.getIssued(), r.getVolume(),
        r.getIssue(), r.getPage(), r.getPublisher(), r.getDoi(), r.getIsbn(), r.getIssn(),
        r.getLink(), r.getRemarks(), r.getVersion());
  }
}
