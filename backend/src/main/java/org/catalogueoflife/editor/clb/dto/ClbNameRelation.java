package org.catalogueoflife.editor.clb.dto;

// A nomenclatural relation of the CLB name (for the comparison view), e.g. "basionym" -> "Felis leo L.".
// id ("relatedUsageId|type") is what the copy action sends; relatedScientificName (no authorship) is
// what our side's relation label is compared against.
public record ClbNameRelation(String id, String type, String relatedName, String relatedScientificName) {}
