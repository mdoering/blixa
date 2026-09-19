package org.catalogueoflife.editor.name.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * Bulk change of the taxonomic status of several usages at once (POST /usages/bulk-status). Only
 * parent-preserving transitions are permitted -- accepted&lt;-&gt;unassessed and
 * synonym&lt;-&gt;misapplied -- so no per-usage parent decision is required; the service rejects any
 * other transition with 400.
 *
 * <p>The usages are selected by exactly one of: explicit {@code ids}; a {@code filter} (the Names
 * search's q/rank/status -- "select all matching"); or {@code subtreeOf}, a taxon whose whole
 * parent_id subtree (itself included) is changed.
 */
public record BulkStatusRequest(List<Integer> ids, Filter filter, Integer subtreeOf,
    @NotBlank String status) {

  /** The Names search filter set (GET /usages q/rank/status), each optional. */
  public record Filter(String q, String rank, String status) {}
}
