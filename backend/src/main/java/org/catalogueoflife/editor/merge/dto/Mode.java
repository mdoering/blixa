package org.catalogueoflife.editor.merge.dto;

// The apply-time reconciliation strategy for MATCHED records, chosen by the curator alongside the
// apply request (MergeRunMapper.startApply persists it on merge_run.mode). Top-level (not nested
// in MergePlan) so MergeApplyService (a later task) can import it directly as Mode.
//
// OVERWRITE   -- a matched target record's differing scalar fields are overwritten from the
//                source, then missing relations (synonyms/children) are added.
// FILL_GAPS   -- only blank target scalars are filled from the source; missing relations are
//                added; a non-blank target value is never overwritten.
// NEW_ONLY    -- matched records are left entirely untouched; only records classified NEW are
//                added.
//
// In every mode, NEW records (no target match) are always added -- mode only governs what happens
// to MATCHED records.
public enum Mode {
  OVERWRITE,
  FILL_GAPS,
  NEW_ONLY
}
