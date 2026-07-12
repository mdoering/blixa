package org.catalogueoflife.editor.name.dto;

import java.util.List;
import life.catalogue.api.model.CslName;

// citationManual is Boolean (not a primitive) so a create/update JSON payload that omits it (the
// overwhelming majority of existing callers, which only ever send `citation`/structured fields, not
// the new flag) still deserializes -- unlike UpdateReferenceRequest.version, there is a sensible
// default (false) for a caller that never mentions it. ReferenceService.create/update coalesce a
// missing value to false.
public record CreateReferenceRequest(
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
    String remarks) {}
