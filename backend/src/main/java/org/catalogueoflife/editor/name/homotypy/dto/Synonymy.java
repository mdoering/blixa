package org.catalogueoflife.editor.name.homotypy.dto;

import java.util.List;

// Nested synonymy of an accepted usage: recombinations homotypic to the accepted name, then each
// heterotypic group (basionym first, its recombinations after), then misapplied names.
public record Synonymy(List<SynonymEntry> homotypic, List<List<SynonymEntry>> heterotypicGroups,
    List<SynonymEntry> misapplied) {}
