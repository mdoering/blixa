package org.catalogueoflife.editor.clb.dto;

import java.util.List;

/**
 * Body of {@code POST /api/projects/{pid}/usages/{focalId}/clb-copy}: copy chosen records of CLB
 * taxon {@code taxonId} onto the (accepted) focal usage -- the per-record "«" actions of the
 * Compare-with-CLB view. Ids are CLB's own (synonym usage ids, vernacular ids, type material ids of
 * the focal name), as listed in {@link ClbComparison}. {@code publishedIn} creates the CLB name's
 * published-in reference in the project (the caller then points the edit form at it).
 */
public record ClbCopyRequest(String datasetKey, String taxonId, List<String> synonymIds,
    List<String> vernacularIds, List<String> typeMaterialIds, Boolean publishedIn) {

  // Boxed + null-safe: the SPA omits publishedIn when copying records, and the app's Jackson config
  // rejects a missing primitive.
  public boolean wantsPublishedIn() {
    return Boolean.TRUE.equals(publishedIn);
  }
}
