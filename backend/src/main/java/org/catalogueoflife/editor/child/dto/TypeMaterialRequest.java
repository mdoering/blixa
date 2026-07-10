package org.catalogueoflife.editor.child.dto;

// Body of create/update for a type material record. `version` is used only by update (optimistic
// lock). occurrenceId lets us later reconcile/import GBIF occurrence records.
public record TypeMaterialRequest(
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
    Integer referenceId,
    String link,
    String remarks,
    Double latitude,
    Double longitude,
    Integer version) {}
