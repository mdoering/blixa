package org.catalogueoflife.editor.name.homotypy.dto;

// A single proposed homotypic relation (recombination usageId -> basionym relatedUsageId, or a
// homotypic chain link). `alreadyExists` is true when a matching name_relation is already stored.
public record ProposedRelation(int usageId, int relatedUsageId, String type, boolean alreadyExists) {}
