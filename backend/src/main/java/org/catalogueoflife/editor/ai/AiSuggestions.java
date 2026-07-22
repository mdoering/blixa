package org.catalogueoflife.editor.ai;

import java.util.List;

// The structured output an LLM fills in for a focal taxon (schema-constrained). Kept flat + simple
// for reliable schema derivation across providers. Lists may be empty; strings may be null/blank.
// Synonyms lead -- the headline output for an accepted taxon.
public record AiSuggestions(
    List<SynonymSuggestion> synonyms,
    List<VernacularSuggestion> vernacularNames,
    List<DistributionSuggestion> distributions,
    List<String> descriptions,
    List<ReferenceSuggestion> references,
    String etymology) {}

// A synonym of the focal accepted taxon: another scientific name + authorship, an optional
// nomenclatural status, and an optional nomenclatural-reference DOI (verified before it's offered).
record SynonymSuggestion(String scientificName, String authorship, String nomStatus, String referenceDoi) {}

record VernacularSuggestion(String name, String language) {}

record DistributionSuggestion(String area) {}

// A key reference for the taxon/name, ideally as a DOI (verified against Crossref/DataCite).
record ReferenceSuggestion(String doi, String citation) {}
