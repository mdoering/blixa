package org.catalogueoflife.editor.name.homotypy.dto;

import java.util.List;

// Body of POST /usages/{survivorId}/homotypic/consolidate: demote each loser accepted name to a
// SYNONYM of the survivor and persist the cluster's homotypic relations.
public record ConsolidateRequest(List<LoserRef> losers,
    List<ApplyHomotypicRequest.ApplyRelation> relations) {
  public record LoserRef(int acceptedId, int version) {}
}
