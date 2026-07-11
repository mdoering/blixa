package org.catalogueoflife.editor.merge.dto;

// One row of a paged mapping-review response (MergeService.getMapping / GET .../{runId}/mapping):
// a Candidate (sourceId/category/targetId/score) enriched with display labels for both sides, so
// the review table needs no extra fetch of its own. sourceLabel/targetLabel are built by
// MergeService.getMapping from the source/target project's name-usage or reference rows (id ->
// record maps built once per call) -- "<scientificName> <authorship> (<rank>)" for a name, the
// citation for a reference. targetLabel is null when targetId is null (category NEW) or when the
// target row can no longer be resolved (deleted after the plan was computed).
public record MappingRow(String sourceId, Category category, String targetId, Double score,
    String sourceLabel, String targetLabel) {}
