package org.catalogueoflife.editor.validation;

import java.util.Set;
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
//  - hasSpeciesAncestor: whether a strict ancestor of rank species exists (InfraspecificMissingSpeciesRule).
//    A dedicated signal rather than reusing ancestorSpeciesEpithet, which is also null when a species
//    ancestor exists but has no parsed specific_epithet.
//  - danglingReferenceCount: how many of the usage's taxonomic reference_id[] point to a reference that
//    no longer exists (DanglingReferenceRule) -- reference_id[] has no array FK, unlike published_in.
//  - duplicateChildTypes: the child-entity types ("distribution"/"vernacular"/"media"/"estimate"/
//    "property") that have a duplicate row group on this usage (DuplicateChildRecordsRule).
//  - synonymRankDiffers: this usage is a synonym whose rank differs from an accepted target's
//    (SynonymRankDiffersRule).
//  - linkedGenusName: the name of the genus usage this binomial is linked to (genus_id), or null when
//    unlinked (LinkedGenusSpellingRule compares it to the parsed genus token).
//  - ancestorGenusId: the id of the nearest classification-ancestor genus (via parent_id), or null --
//    an accepted binomial's genus_id should equal it (AcceptedGenusLinkRule).
//
// The 4-, 5-, 9-, 11-, 13-, and 14-arg convenience constructors default the later fields, so the
// hand-built contexts in RuleTests don't need updating.
public record RuleContext(NameUsage usage, Integer synonymAcceptedCount, Reference publishedInReference,
    int duplicateCount, String ancestorGenusName, String parentRank, Integer ancestorGenusYear,
    String ancestorSpeciesEpithet, int synonymNonAcceptedTargetCount, boolean hasSpeciesAncestor,
    int danglingReferenceCount, Set<String> duplicateChildTypes, boolean synonymRankDiffers,
    String linkedGenusName, Integer ancestorGenusId) {

  public RuleContext(NameUsage usage, Integer synonymAcceptedCount, Reference publishedInReference,
      int duplicateCount) {
    this(usage, synonymAcceptedCount, publishedInReference, duplicateCount, null, null, null, null, 0,
        false, 0, Set.of(), false, null, null);
  }

  public RuleContext(NameUsage usage, Integer synonymAcceptedCount, Reference publishedInReference,
      int duplicateCount, String ancestorGenusName) {
    this(usage, synonymAcceptedCount, publishedInReference, duplicateCount, ancestorGenusName, null, null,
        null, 0, false, 0, Set.of(), false, null, null);
  }

  public RuleContext(NameUsage usage, Integer synonymAcceptedCount, Reference publishedInReference,
      int duplicateCount, String ancestorGenusName, String parentRank, Integer ancestorGenusYear,
      String ancestorSpeciesEpithet, int synonymNonAcceptedTargetCount) {
    this(usage, synonymAcceptedCount, publishedInReference, duplicateCount, ancestorGenusName, parentRank,
        ancestorGenusYear, ancestorSpeciesEpithet, synonymNonAcceptedTargetCount, false, 0, Set.of(), false, null, null);
  }

  public RuleContext(NameUsage usage, Integer synonymAcceptedCount, Reference publishedInReference,
      int duplicateCount, String ancestorGenusName, String parentRank, Integer ancestorGenusYear,
      String ancestorSpeciesEpithet, int synonymNonAcceptedTargetCount, boolean hasSpeciesAncestor,
      int danglingReferenceCount) {
    this(usage, synonymAcceptedCount, publishedInReference, duplicateCount, ancestorGenusName, parentRank,
        ancestorGenusYear, ancestorSpeciesEpithet, synonymNonAcceptedTargetCount, hasSpeciesAncestor,
        danglingReferenceCount, Set.of(), false, null, null);
  }

  public RuleContext(NameUsage usage, Integer synonymAcceptedCount, Reference publishedInReference,
      int duplicateCount, String ancestorGenusName, String parentRank, Integer ancestorGenusYear,
      String ancestorSpeciesEpithet, int synonymNonAcceptedTargetCount, boolean hasSpeciesAncestor,
      int danglingReferenceCount, Set<String> duplicateChildTypes, boolean synonymRankDiffers) {
    this(usage, synonymAcceptedCount, publishedInReference, duplicateCount, ancestorGenusName, parentRank,
        ancestorGenusYear, ancestorSpeciesEpithet, synonymNonAcceptedTargetCount, hasSpeciesAncestor,
        danglingReferenceCount, duplicateChildTypes, synonymRankDiffers, null, null);
  }

  public RuleContext(NameUsage usage, Integer synonymAcceptedCount, Reference publishedInReference,
      int duplicateCount, String ancestorGenusName, String parentRank, Integer ancestorGenusYear,
      String ancestorSpeciesEpithet, int synonymNonAcceptedTargetCount, boolean hasSpeciesAncestor,
      int danglingReferenceCount, Set<String> duplicateChildTypes, boolean synonymRankDiffers,
      String linkedGenusName) {
    this(usage, synonymAcceptedCount, publishedInReference, duplicateCount, ancestorGenusName, parentRank,
        ancestorGenusYear, ancestorSpeciesEpithet, synonymNonAcceptedTargetCount, hasSpeciesAncestor,
        danglingReferenceCount, duplicateChildTypes, synonymRankDiffers, linkedGenusName, null);
  }
}
