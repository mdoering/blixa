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
    // "scientificName authorship" of the accepted name when this CLB usage is a synonym, else null.
    String acceptedName,
    List<ClbRankName> classification,
    List<ClbSynonym> synonyms,
    List<ClbVernacular> vernacularNames,
    String etymology,
    // lower-case gender of the name (genus-level names), e.g. "feminine"
    String gender,
    // citation of the name's published-in reference, and the page within it
    String publishedIn,
    String publishedInPage,
    List<ClbTypeMaterial> typeMaterial,
    List<ClbNameRelation> nameRelations) {}
