package org.catalogueoflife.editor.clb.dto;

// A rank + name pair in a CLB taxon's higher classification.
public record ClbRankName(String rank, String name) {}
