package org.catalogueoflife.editor.name.homotypy.dto;

// One display row in a synonymy. formattedName is the parser-rendered (italic-markup-free) name;
// the UI decides ≡/= from the entry's position (homotypic list vs a heterotypic group's basionym).
public record SynonymEntry(int id, String scientificName, String authorship, String rank,
    String status, String formattedName) {}
