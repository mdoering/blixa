package org.catalogueoflife.editor.mergerecords.dto;
import java.util.List;
import java.util.Map;
// One selected usage in a merge preview: display fields + its association counts (children, synonyms,
// acceptedOf, nameRelations, vernacular, distribution, media, typeMaterial, property, estimate).
// nameRelations already covers a basionym relation (name_relation carries no dedicated basionym
// count of its own -- see MergeRecordsMapper.usageCounts).
public record UsageMergeCandidate(int id, List<String> alternativeId, String scientificName,
    String authorship, String rank, String status, Map<String, Integer> counts) {}
