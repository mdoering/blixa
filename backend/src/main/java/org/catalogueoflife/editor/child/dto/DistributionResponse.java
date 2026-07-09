package org.catalogueoflife.editor.child.dto;

public record DistributionResponse(
    Integer id,
    Integer usageId,
    String area,
    String areaId,
    String gazetteer,
    String establishmentMeans,
    String threatStatus,
    Integer referenceId,
    String remarks,
    Integer version) {}
