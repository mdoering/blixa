package org.catalogueoflife.editor.name;

// The name_usage.status column is TEXT storing this enum's name() (MyBatis's default
// EnumTypeHandler auto-applies to any enum-typed scalar property -- no custom typeHandler
// needed). This is a COL-editor-specific simplification, NOT life.catalogue.api.vocab's
// TaxonomicStatus (which additionally has PROVISIONALLY_ACCEPTED/AMBIGUOUS_SYNONYM/BARE_NAME):
// the phase-1 editing model collapses taxonomic status down to these four values.
public enum Status {
  ACCEPTED,
  SYNONYM,
  MISAPPLIED,
  UNASSESSED;

  // A "taxon" is a node in the classification tree with a parent_id and its own taxon-level data
  // (taxon_info + vernacular/distribution/media/estimate/property): ACCEPTED and UNASSESSED
  // ("provisionally accepted"). SYNONYM/MISAPPLIED instead attach to an accepted name via a
  // synonym_accepted link. Used across the tree, child-entity and import/export code so the
  // accepted-vs-provisional distinction is expressed in exactly one place.
  public boolean isTaxon() {
    return this == ACCEPTED || this == UNASSESSED;
  }
}
