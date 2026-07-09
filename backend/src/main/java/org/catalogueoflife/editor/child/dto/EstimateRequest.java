package org.catalogueoflife.editor.child.dto;

public record EstimateRequest(
    Integer estimate,
    String type,
    Integer referenceId,
    String remarks,
    Integer version) {}
