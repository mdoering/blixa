package org.catalogueoflife.editor.name.bulk;

// CHILDREN: each top-level node becomes an accepted child of the target (nested hierarchy + = synonyms
// preserved). SYNONYMS: the input must be a flat list; each line becomes a synonym of the target.
public enum BulkMode {
  CHILDREN,
  SYNONYMS
}
