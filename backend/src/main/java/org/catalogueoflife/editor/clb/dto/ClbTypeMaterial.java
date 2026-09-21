package org.catalogueoflife.editor.clb.dto;

// A type material record of the CLB name (for the comparison view); id is CLB's, used to copy it over.
public record ClbTypeMaterial(String id, String status, String citation, String catalogNumber,
    String institutionCode, String locality) {}
