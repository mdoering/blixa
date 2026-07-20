package org.catalogueoflife.editor.name;

// How much to remove when deleting a taxon (see NameUsageService.delete):
//  FOCAL_ONLY    - just the focal; its accepted children reparent to the focal's parent (or a chosen
//                  target), its synonyms unlink and survive.
//  WITH_SYNONYMS - the focal and its synonyms; children reparent as above.
//  SUBTREE       - the focal, all its descendants, and all their synonyms.
public enum DeleteMode {
  FOCAL_ONLY,
  WITH_SYNONYMS,
  SUBTREE
}
