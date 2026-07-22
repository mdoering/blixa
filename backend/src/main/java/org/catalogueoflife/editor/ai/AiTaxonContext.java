package org.catalogueoflife.editor.ai;

import java.util.List;

// The focal-taxon facts handed to the LLM so it suggests supplementary data grounded in what the
// project already has (and doesn't re-suggest existing synonyms). Assembled by AiSuggestionService.
public record AiTaxonContext(
    String scientificName,
    String authorship,
    String rank,
    String nomCode,
    List<String> existingSynonyms) {}
