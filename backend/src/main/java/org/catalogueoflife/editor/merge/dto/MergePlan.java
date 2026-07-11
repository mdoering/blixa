package org.catalogueoflife.editor.merge.dto;

import java.util.List;

// The whole dry-run merge plan for one merge_run: every source reference and every source
// name-usage, each categorized against the target project by NameMatcher/ReferenceMatcher (later
// tasks). Serialized as-is to merge_run.plan (JSONB; see V19__merge_run.sql) by MergeService, and
// re-read/filtered by MergeService.getMapping and edited in place by applyOverrides -- there is no
// separate table per candidate, the plan is one JSONB blob for the whole run.
public record MergePlan(List<Candidate> references, List<Candidate> names) {}
