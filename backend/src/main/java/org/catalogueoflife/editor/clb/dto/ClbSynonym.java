package org.catalogueoflife.editor.clb.dto;

// A synonym of a CLB taxon (for the comparison view); id is its CLB usage id, used to copy it over.
public record ClbSynonym(String scientificName, String authorship, String status, String id) {}
