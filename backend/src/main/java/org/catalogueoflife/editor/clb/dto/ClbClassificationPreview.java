package org.catalogueoflife.editor.clb.dto;

import java.util.List;

// GET .../clb-classification: the CLB higher classification resolved against our tree, plus where
// the focal usage currently sits (its parent), so the UI can say where it will end up.
public record ClbClassificationPreview(List<ClbClassificationStep> steps, Integer currentParentId,
    String currentParentName) {}
