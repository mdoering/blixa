package org.catalogueoflife.editor.clb.dto;

import java.util.List;

// POST .../clb-classification: wire the focal usage into the tree along CLB taxon {@code taxonId}'s
// classification, creating the missing ancestors listed in {@code createClbIds} (CLB ids).
public record ClbClassificationRequest(String datasetKey, String taxonId, List<String> createClbIds) {}
