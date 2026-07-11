package org.catalogueoflife.editor.merge.dto;

// One source record's match result, produced by NameMatcher/ReferenceMatcher (later tasks) and
// stored in MergePlan.names/references. sourceId/targetId are entity ids as strings (a name-usage
// id or a reference id, both scoped to their own project) rather than typed ids: the same shape is
// reused for two different entity types (see MergePlan), and ids only ever need to round-trip
// through JSON and mapper lookups, never arithmetic. targetId is null for category NEW (no match);
// score is 1.0 for MATCHED (an exact canonical-key/DOI/citation match -- no similarity computed,
// reported as full confidence) and null for NEW, POSSIBLE_HOMONYM, and an ambiguous-exact-match
// POSSIBLE; POSSIBLE_FUZZY (and a fuzzy-citation POSSIBLE) carry the trigram similarity that
// produced the candidate.
public record Candidate(String sourceId, Category category, String targetId, Double score) {}
