package org.catalogueoflife.editor.child.dto;

// API projection of a type_material row. MyBatis maps the SELECT to this record by constructor
// (underscore->camel + -parameters).
public record TypeMaterialResponse(
    Integer id,
    Integer usageId,
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
    Integer version) {}
