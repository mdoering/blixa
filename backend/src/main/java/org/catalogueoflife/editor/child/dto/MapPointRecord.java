package org.catalogueoflife.editor.child.dto;

// One type-specimen point for the subtree map: only type_material rows carrying BOTH latitude AND
// longitude are ever selected (see MapDataMapper.findSubtreeTypePoints), so latitude/longitude are
// non-null whenever a MapPointRecord exists.
public record MapPointRecord(
    Integer usageId,
    String name,
    boolean focal,
    String status,
    Double latitude,
    Double longitude,
    String locality) {}
