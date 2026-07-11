package org.catalogueoflife.editor.clb;

// The three Direct CLB Taxon Import entry points ClbImportService.importFromClb branches on:
//  - TAXON_SUBTREE: insert the picked CLB taxon itself PLUS its whole descendant tree
//    (childrenIds, recursively) under the focal project usage.
//  - CHILDREN_ONLY: insert only the picked taxon's descendants (same recursion as
//    TAXON_SUBTREE) -- the picked taxon itself is NOT inserted; its direct children land
//    straight under the focal usage instead. Useful when the focal usage already IS that CLB
//    taxon (e.g. matched earlier) and only its children are still missing.
//  - UPDATE_FOCAL: insert no new accepted usages at all. Instead, the picked CLB taxon's own
//    supplementary data (synonyms / child entities, gated by ClbImportRequest.entityTypes) is
//    attached directly onto the EXISTING focal usage.
public enum ImportMode {
  TAXON_SUBTREE,
  CHILDREN_ONLY,
  UPDATE_FOCAL
}
