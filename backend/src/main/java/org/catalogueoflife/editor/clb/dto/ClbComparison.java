package org.catalogueoflife.editor.clb.dto;

import java.util.List;

// The CLB side of a focal-taxon comparison, built from ClbImportClient.usageInfo(). The "ours" side
// is assembled on the frontend from the focal usage + its classification/synonyms.
public record ClbComparison(
    String datasetKey,
    String datasetTitle,
    String taxonId,
    String link,
    String scientificName,
    String authorship,
    String rank,
    String status,
    List<ClbRankName> classification,
    List<ClbSynonym> synonyms) {}
