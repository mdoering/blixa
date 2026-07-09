package org.catalogueoflife.editor.child.dto;

public record PropertyResponse(
    Integer id,
    Integer usageId,
    String property,
    String value,
    String page,
    Integer referenceId,
    String remarks,
    Integer version) {}
