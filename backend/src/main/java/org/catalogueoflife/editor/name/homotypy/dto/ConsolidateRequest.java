package org.catalogueoflife.editor.name.homotypy.dto;

import java.util.List;

// Body of POST /usages/{survivorId}/homotypic/consolidate: demote each loser accepted MEMBER to a
// SYNONYM of the survivor, re-point each synonym MEMBER (repoint) to the survivor -- unlinking it
// from its other accepted targets -- and persist the cluster's homotypic relations. An accepted
// name reached only as a synonym's target (not itself a cluster member) is never demoted.
public record ConsolidateRequest(List<LoserRef> losers, List<Integer> repoint,
    List<ApplyHomotypicRequest.ApplyRelation> relations) {
  public record LoserRef(int acceptedId, int version) {}
}
