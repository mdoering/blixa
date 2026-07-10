package org.catalogueoflife.editor.child.dto;

// One distribution row for the subtree map: the usage it belongs to (which may be the focal usage
// itself or any descendant), its name (for labeling), whether it IS the focal usage, and the
// distribution's area. MyBatis maps the SELECT's aliased columns to this record by constructor
// (underscore->camel + -parameters); `focal` is computed in SQL as a boolean expression
// (`usage_id = #{usageId}`) and maps straight onto the `boolean` field.
public record MapAreaRecord(
    Integer usageId,
    String name,
    boolean focal,
    String gazetteer,
    String areaId,
    String area) {}
