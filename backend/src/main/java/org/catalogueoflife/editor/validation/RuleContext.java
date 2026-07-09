package org.catalogueoflife.editor.validation;

import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.Reference;

// Immutable, built once per usage by ValidationService.buildContext and handed to every rule.
// Task-1 fields only (later tasks extend this with e.g. parent() for the tree-aware rules):
//  - usage: the name_usage row being validated.
//  - synonymAcceptedCount: how many synonym_accepted rows link this usage as a synonym (0 for a
//    usage that isn't a synonym/misapplied name, since ValidationService computes it unconditionally).
//  - publishedInReference: the reference usage.publishedInReferenceId points to, or null if unset
//    or the id doesn't resolve.
//  - duplicateCount: how many OTHER usages in the project share the same scientificName+authorship.
//  - ancestorGenusName: scientific name of the nearest ACCEPTED ancestor of rank genus (null if the
//    usage has none), for GenusMismatchRule. The 4-arg convenience constructor leaves it null so the
//    hand-built RuleContexts in RuleTests don't need updating.
public record RuleContext(NameUsage usage, Integer synonymAcceptedCount, Reference publishedInReference,
    int duplicateCount, String ancestorGenusName) {

  public RuleContext(NameUsage usage, Integer synonymAcceptedCount, Reference publishedInReference,
      int duplicateCount) {
    this(usage, synonymAcceptedCount, publishedInReference, duplicateCount, null);
  }
}
