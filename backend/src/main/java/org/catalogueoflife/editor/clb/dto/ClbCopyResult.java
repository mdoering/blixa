package org.catalogueoflife.editor.clb.dto;

// Result of a Compare-with-CLB copy: the usual import summary, plus the id of the reference created
// for the CLB published-in citation (null unless requested and present).
public record ClbCopyResult(ClbImportSummary summary, Integer publishedInReferenceId) {}
