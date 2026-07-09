package org.catalogueoflife.editor.child.dto;

public record PropertyRequest(
    String property,
    String value,
    String page,
    Integer referenceId,
    String remarks,
    Integer version) {}
