package org.catalogueoflife.editor.child.dto;

public record VernacularResponse(
    Integer id,
    Integer usageId,
    String name,
    String language,
    String country,
    String sex,
    Boolean preferred,
    Integer referenceId,
    String remarks,
    Integer version) {}
