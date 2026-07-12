package org.catalogueoflife.editor.name.dto;

import java.util.List;
import life.catalogue.api.model.CslName;

// citationManual is Boolean, not a primitive -- see CreateReferenceRequest's javadoc for why.
public record UpdateReferenceRequest(
    String citation,
    Boolean citationManual,
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
    int version) {}
