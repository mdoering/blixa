package org.catalogueoflife.editor.name.dto;

// A single usage id plus the trigram similarity score that ranked it (NameUsageMapper.
// findFuzzyCandidate) -- merge.NameMatcher's POSSIBLE_FUZZY candidate source when no
// exact canonical-key match exists in the target project.
public record ScoredId(int id, double score) {}
