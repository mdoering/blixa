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
    String accessed,
    String remarks,
    Integer version,
    String pdfUrl) {

  // pdfBaseUrl is coldp.pdf.base-url (see application.yml) -- threaded in from the caller (every
  // controller building a ReferenceResponse holds it via @Value) rather than read off the entity,
  // since Reference.pdf is only ever a bare filename, never a URL.
  public static ReferenceResponse of(Reference r, String pdfBaseUrl) {
    String pdfUrl = r.getPdf() == null ? null : pdfBaseUrl + "/" + r.getPdf();
    return new ReferenceResponse(r.getId(), r.getCitation(), r.getType(), r.getAuthor(),
        r.getEditor(), r.getTitle(), r.getContainerTitle(), r.getIssued(), r.getVolume(),
        r.getIssue(), r.getPage(), r.getPublisher(), r.getDoi(), r.getIsbn(), r.getIssn(),
        r.getLink(), r.getAccessed(), r.getRemarks(), r.getVersion(), pdfUrl);
  }
}
