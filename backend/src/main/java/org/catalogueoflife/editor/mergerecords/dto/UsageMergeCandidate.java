package org.catalogueoflife.editor.mergerecords.dto;
import java.util.List;
import java.util.Map;
// One selected usage in a merge preview: display fields + its association counts (children, synonyms,
// acceptedOf, basionymOf, nameRelations, vernacular, distribution, media, typeMaterial, property, estimate).
public record UsageMergeCandidate(int id, List<String> alternativeId, String scientificName,
    String authorship, String rank, String status, Map<String, Integer> counts) {}
