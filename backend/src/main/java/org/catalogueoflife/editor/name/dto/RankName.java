package org.catalogueoflife.editor.name.dto;

// One higher-classification entry (NameUsageMapper.findClassification), root-first, self excluded.
// `name` is the ancestor's scientificName -- higher taxa are uninomials, so that IS the taxon
// name -- fed to the COL name matcher as a higher-classification query param (see Task 4).
public record RankName(String rank, String name) {}
