package org.catalogueoflife.editor.clb.dto;

// Outcome of wiring the focal usage into the tree: its (new) parent, how many ancestors were
// created, and whether it was actually moved (false when it already sat there).
public record ClbClassificationResult(Integer parentId, int created, boolean moved) {}
