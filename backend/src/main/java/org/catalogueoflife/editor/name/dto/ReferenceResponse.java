package org.catalogueoflife.editor.name.dto;

import java.util.List;
import life.catalogue.api.model.CslName;
import org.catalogueoflife.editor.name.Reference;

public record ReferenceResponse(
    Integer id,
    String citation,
    boolean citationManual,
    String type,
    List<CslName> author,
    List<CslName> editor,
    String title,
    String containerTitle,
    String containerTitleShort,
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
    String pdfUrl,
    Integer bhlItemId) {

  // pdfBaseUrl is coldp.pdf.base-url (see application.yml) -- threaded in from the caller (every
  // controller building a ReferenceResponse holds it via @Value) rather than read off the entity,
  // since Reference.pdf is only ever a bare filename, never a URL.
  public static ReferenceResponse of(Reference r, String pdfBaseUrl) {
    String pdfUrl = r.getPdf() == null ? null : pdfBaseUrl + "/" + r.getPdf();
    return new ReferenceResponse(r.getId(), r.getCitation(), r.isCitationManual(), r.getType(),
        r.getAuthor(), r.getEditor(), r.getTitle(), r.getContainerTitle(), r.getContainerTitleShort(),
        r.getIssued(), r.getVolume(), r.getIssue(), r.getPage(), r.getPublisher(), r.getDoi(),
        r.getIsbn(), r.getIssn(), r.getLink(), r.getAccessed(), r.getRemarks(), r.getVersion(), pdfUrl,
        r.getBhlItemId());
  }
}
