package org.catalogueoflife.editor.merge.dto;

// A source record's match outcome against the target project, assigned by NameMatcher/
// ReferenceMatcher (later tasks) and carried on every Candidate. Top-level (not nested in
// MergePlan) so matchers, MergeService/MergeApplyService, and the mapping-review endpoint can all
// import it directly as Category rather than MergePlan.Category.
//
// MATCHED           -- exactly one author-/DOI-/citation-compatible target record found; the
//                      source record's target id is stable across the merge.
// POSSIBLE_HOMONYM   -- same canonical key/citation but no author-/DOI-compatible target found
//                      (or ambiguous authorship) -- surfaced for review, never silently merged.
// POSSIBLE_FUZZY     -- no exact canonical/DOI/citation candidate, but a trigram-similar target
//                      cleared the configured similarity threshold.
// POSSIBLE           -- more than one equally plausible target candidate (ambiguous exact match,
//                      or a fuzzy citation candidate for references) -- needs a curator's pick.
// NEW                -- no target candidate at all; the source record is added as a new record.
public enum Category {
  MATCHED,
  POSSIBLE_HOMONYM,
  POSSIBLE_FUZZY,
  POSSIBLE,
  NEW
}
