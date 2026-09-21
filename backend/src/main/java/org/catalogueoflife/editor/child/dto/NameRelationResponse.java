package org.catalogueoflife.editor.child.dto;

// API projection of a name_relation row + the owning and related usages' scientific names (LEFT
// JOINs, for display). MyBatis maps the SELECT to this record by constructor (underscore->camel + -parameters).
public record NameRelationResponse(
    Integer id,
    Integer usageId,
    String usageName,
    Integer relatedUsageId,
    String relatedName,
    String type,
    Integer referenceId,
    String page,
    String remarks,
    Integer version) {}
