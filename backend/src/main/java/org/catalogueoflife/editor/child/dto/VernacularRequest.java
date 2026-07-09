package org.catalogueoflife.editor.child.dto;

public record VernacularRequest(
    String name,
    String language,
    String country,
    String sex,
    Boolean preferred,
    Integer referenceId,
    String remarks,
    Integer version) {}
