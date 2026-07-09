package org.catalogueoflife.editor.child.dto;

// Body of create/update for a name relation. `version` is used only by update (optimistic lock).
public record NameRelationRequest(
    Integer relatedUsageId,
    String type,
    Integer referenceId,
    String page,
    String remarks,
    Integer version) {}
