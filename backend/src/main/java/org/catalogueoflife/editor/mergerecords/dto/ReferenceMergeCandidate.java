package org.catalogueoflife.editor.mergerecords.dto;
import java.util.List;
import java.util.Map;
// One selected reference in a merge preview: display fields + its citing-association counts
// (publishedIn, citedBy, nameRelations, typeMaterial, vernacular, distribution, estimate, property).
public record ReferenceMergeCandidate(int id, List<String> alternativeId, String citation, String doi,
    Map<String, Integer> counts) {}
