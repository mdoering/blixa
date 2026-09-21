package org.catalogueoflife.editor.clb.dto;

// A nomenclatural relation of the CLB name (for the comparison view), e.g. "basionym" -> "Felis leo L.".
public record ClbNameRelation(String type, String relatedName) {}
