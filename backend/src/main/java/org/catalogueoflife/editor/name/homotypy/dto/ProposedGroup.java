package org.catalogueoflife.editor.name.homotypy.dto;

import java.util.List;

// One detected homotypic group. basionymUsageId is null when no basionym was discernible (members
// are then chained as `homotypic`). memberUsageIds includes every usage in the group (basionym +
// recombinations). relations is empty for a singleton group.
public record ProposedGroup(Integer basionymUsageId, List<Integer> memberUsageIds,
    List<ProposedRelation> relations) {}
