package org.catalogueoflife.editor.name.dto;

// One candidate DOI for an existing reference, from a Crossref bibliographic search over its
// structured fields (DOI consolidation -- the inverse of resolve-doi). `score` is Crossref's own
// relevance score for the query, surfaced so the UI can order/flag weak matches; the user always
// confirms before the DOI is applied.
public record DoiCandidate(
    String doi,
    String title,
    String author,
    String containerTitle,
    String year,
    Double score) {}
