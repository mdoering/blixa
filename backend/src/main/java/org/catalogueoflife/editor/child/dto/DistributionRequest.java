package org.catalogueoflife.editor.child.dto;

// Distribution for an accepted taxon: either a free-text `area` OR a gazetteer-coded `areaId` +
// `gazetteer` (preferred, so we can later show maps as the portal components do).
public record DistributionRequest(
    String area,
    String areaId,
    String gazetteer,
    String establishmentMeans,
    String threatStatus,
    Integer referenceId,
    String remarks,
    Integer version) {}
