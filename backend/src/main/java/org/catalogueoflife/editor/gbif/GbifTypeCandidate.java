package org.catalogueoflife.editor.gbif;

// One GBIF type-specimen occurrence mapped onto TypeMaterial fields (see the design's mapping table),
// plus `alreadyImported` (an existing TypeMaterial on this usage already carries this occurrenceId).
// The frontend imports a ticked candidate by POSTing these fields to the existing
// POST /usages/{id}/type-material create endpoint.
public record GbifTypeCandidate(
    String citation,
    String status,
    String institutionCode,
    String catalogNumber,
    String occurrenceId,
    String locality,
    String country,
    String collector,
    String date,
    String sex,
    String link,
    Double latitude,
    Double longitude,
    boolean alreadyImported) {}
