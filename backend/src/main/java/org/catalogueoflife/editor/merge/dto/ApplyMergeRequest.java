package org.catalogueoflife.editor.merge.dto;

// POST .../merge/{runId}/apply's request body. mode is required (there is no sensible default
// among OVERWRITE/FILL_GAPS/NEW_ONLY for how a MATCHED record should be reconciled -- see Mode's
// javadoc); transactional defaults to true when omitted -- MergeApplyService.apply's normal,
// safest path (the whole apply rolls back as one unit on any failure). Task 7 adds real
// per-entity-type batching for the non-transactional (transactional=false) case.
public record ApplyMergeRequest(Mode mode, Boolean transactional) {}
