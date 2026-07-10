package org.catalogueoflife.editor.col;

// Per-usage result of ColMatchJobService.matchOne -- also the exact per-outcome tally column name
// on col_match_run (lower-cased) that ColMatchRunMapper.tick increments.
public enum ColOutcome {
  VERIFIED, ADDED, UPDATED, UNMATCHED
}
