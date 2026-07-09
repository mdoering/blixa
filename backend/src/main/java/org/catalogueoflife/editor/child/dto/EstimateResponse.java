package org.catalogueoflife.editor.child.dto;

public record EstimateResponse(
    Integer id,
    Integer usageId,
    Integer estimate,
    String type,
    Integer referenceId,
    String remarks,
    Integer version) {}
