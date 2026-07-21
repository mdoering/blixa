package org.catalogueoflife.editor.name.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Bulk change of the taxonomic status of several usages at once (POST /usages/bulk-status). Only
 * parent-preserving transitions are permitted -- accepted&lt;-&gt;unassessed and
 * synonym&lt;-&gt;misapplied -- so no per-usage parent decision is required; the service rejects any
 * other transition with 400.
 */
public record BulkStatusRequest(@NotEmpty List<Integer> ids, @NotBlank String status) {}
