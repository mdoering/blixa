package org.catalogueoflife.editor.merge.dto;

// One curator-submitted correction to a single Candidate already sitting in a PLANNED run's stored
// plan (MergeService.applyOverrides, Task 5): confirm a POSSIBLE_* into a MATCHED with a chosen
// targetId, reject a MATCHED back to NEW (targetId forced null), or re-point an existing MATCHED to
// a different targetId. `entity` selects which half of the MergePlan to look the Candidate up in
// ("name" -> MergePlan.names, "reference" -> MergePlan.references, same convention as
// MergeService.getMapping's `entity` query param); `sourceId` identifies the Candidate within that
// list. `targetId` is required (and validated to exist in the target project) when `category` is
// MATCHED, and ignored (forced to null) when `category` is NEW -- POSSIBLE_HOMONYM/POSSIBLE_FUZZY/
// POSSIBLE are not valid override categories, only a curator's two decisions (confirm or reject) are.
public record MergeOverride(String entity, String sourceId, Category category, String targetId) {}
