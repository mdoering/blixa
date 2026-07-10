package org.catalogueoflife.editor.col;

// Per-usage-per-scope result of ColMatchJobService.matchOneScope -- also the exact per-outcome tally column name
// on col_match_run (lower-cased) that ColMatchRunMapper.tick increments.
public enum ColOutcome {
  VERIFIED, ADDED, UPDATED, UNMATCHED
}
