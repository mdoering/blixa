package org.catalogueoflife.editor.validation;

import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.Reference;

// Immutable, built once per usage by ValidationService.buildContext and handed to every rule.
//  - usage: the name_usage row being validated.
//  - synonymAcceptedCount: how many synonym_accepted rows link this usage as a synonym.
//  - publishedInReference: the reference usage.publishedInReferenceId points to, or null.
//  - duplicateCount: how many OTHER usages in the project share the same scientificName+authorship.
//  - ancestorGenusName: scientific name of the nearest ACCEPTED ancestor of rank genus (GenusMismatchRule).
//  - parentRank: the immediate parent usage's rank (RankVsParentRule), or null at the tree root.
//  - ancestorGenusYear: publishedInYear of the nearest genus ancestor (GenusYearAfterSpeciesRule).
//  - ancestorSpeciesEpithet: specificEpithet of the nearest species ancestor (SpeciesEpithetMismatchRule).
//  - synonymNonAcceptedTargetCount: how many of this synonym's accepted targets are NOT accepted
//    (SynonymOfNonAcceptedRule).
//
// The 4-arg and 5-arg convenience constructors default the tree-context fields, so the hand-built
// contexts in RuleTests (Task-1 base rules + genus rule) don't need updating.
public record RuleContext(NameUsage usage, Integer synonymAcceptedCount, Reference publishedInReference,
    int duplicateCount, String ancestorGenusName, String parentRank, Integer ancestorGenusYear,
    String ancestorSpeciesEpithet, int synonymNonAcceptedTargetCount) {

  public RuleContext(NameUsage usage, Integer synonymAcceptedCount, Reference publishedInReference,
      int duplicateCount) {
    this(usage, synonymAcceptedCount, publishedInReference, duplicateCount, null, null, null, null, 0);
  }

  public RuleContext(NameUsage usage, Integer synonymAcceptedCount, Reference publishedInReference,
      int duplicateCount, String ancestorGenusName) {
    this(usage, synonymAcceptedCount, publishedInReference, duplicateCount, ancestorGenusName, null, null,
        null, 0);
  }
}
