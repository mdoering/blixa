package org.catalogueoflife.editor.clb.dto;

// A vernacular name of a CLB taxon (for the comparison view); id is CLB's, used to copy it over.
public record ClbVernacular(String id, String name, String language, String country) {}
