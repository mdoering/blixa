package org.catalogueoflife.editor.clb.dto;

// A synonym of a CLB taxon (for the comparison view).
public record ClbSynonym(String scientificName, String authorship, String status) {}
