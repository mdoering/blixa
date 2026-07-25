package org.catalogueoflife.editor.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import life.catalogue.api.vocab.Gender;
import org.gbif.nameparser.api.NameType;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.Reference;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.validation.rules.BinomialAboveGenusRule;
import org.catalogueoflife.editor.validation.rules.DanglingReferenceRule;
import org.catalogueoflife.editor.validation.rules.AcceptedGenusLinkRule;
import org.catalogueoflife.editor.validation.rules.DuplicateChildRecordsRule;
import org.catalogueoflife.editor.validation.rules.LinkedGenusSpellingRule;
import org.catalogueoflife.editor.validation.rules.SynonymRankDiffersRule;
import org.catalogueoflife.editor.validation.rules.SuperfluousAuthorshipRule;
import org.catalogueoflife.editor.validation.rules.SuspiciousNameCharactersRule;
import org.catalogueoflife.editor.validation.rules.GenderNotApplicableRule;
import org.catalogueoflife.editor.validation.rules.MissingGenusRule;
import org.catalogueoflife.editor.validation.rules.UppercaseEpithetRule;
import org.catalogueoflife.editor.validation.rules.DuplicateNameRule;
import org.catalogueoflife.editor.validation.rules.GenusMismatchRule;
import org.catalogueoflife.editor.validation.rules.InfraspecificMissingSpeciesRule;
import org.catalogueoflife.editor.validation.rules.GenusYearAfterSpeciesRule;
import org.catalogueoflife.editor.validation.rules.MissingPublishedInRule;
import org.catalogueoflife.editor.validation.rules.RankVsParentRule;
import org.catalogueoflife.editor.validation.rules.SpeciesEpithetMismatchRule;
import org.catalogueoflife.editor.validation.rules.SynonymOfNonAcceptedRule;
import org.catalogueoflife.editor.validation.rules.SynonymWithoutAcceptedRule;
import org.catalogueoflife.editor.validation.rules.UnparsableNameRule;
import org.catalogueoflife.editor.validation.rules.YearVsReferenceRule;
import org.junit.jupiter.api.Test;

// Plain unit tests (no Spring, no DB) for the Task-1 starter rules: each RuleContext is hand-built
// (a plain record, see RuleContext.java) so a rule's boundary behaviour can be asserted directly,
// mirroring parse/NameParserServiceTest's style.
class RuleTests {

  private static NameUsage usage() {
    NameUsage u = new NameUsage();
    u.setScientificName("Abies alba");
    u.setAuthorship("Mill.");
    u.setStatus(Status.ACCEPTED);
    return u;
  }

  private static Reference referenceIssued(String issued) {
    Reference r = new Reference();
    r.setIssued(issued);
    return r;
  }

  // --- UnparsableNameRule ---

  @Test
  void unparsableNameRuleOkWhenComplete() {
    NameUsage u = usage();
    u.setParseState("COMPLETE");

    assertThat(new UnparsableNameRule().evaluate(new RuleContext(u, 0, null, 0))).isEmpty();
  }

  @Test
  void unparsableNameRuleFindsWhenNotComplete() {
    NameUsage u = usage();
    u.setParseState("NONE");

    Optional<Finding> finding = new UnparsableNameRule().evaluate(new RuleContext(u, 0, null, 0));
    assertThat(finding).isPresent();
    assertThat(finding.get().rule()).isEqualTo("unparsable_name");
    assertThat(finding.get().severity()).isEqualTo(Severity.WARNING);
    assertThat(finding.get().message()).contains("NONE");
  }

  @Test
  void unparsableNameRuleOkWhenParseStateNull() {
    NameUsage u = usage(); // parseState never set

    assertThat(new UnparsableNameRule().evaluate(new RuleContext(u, 0, null, 0))).isEmpty();
  }

  // --- SynonymWithoutAcceptedRule ---

  @Test
  void synonymWithoutAcceptedRuleFindsWhenZeroAccepted() {
    NameUsage u = usage();
    u.setStatus(Status.SYNONYM);

    Optional<Finding> finding = new SynonymWithoutAcceptedRule().evaluate(new RuleContext(u, 0, null, 0));
    assertThat(finding).isPresent();
    assertThat(finding.get().severity()).isEqualTo(Severity.ERROR);
  }

  @Test
  void synonymWithoutAcceptedRuleOkWhenLinkedToOneAccepted() {
    NameUsage u = usage();
    u.setStatus(Status.SYNONYM);

    assertThat(new SynonymWithoutAcceptedRule().evaluate(new RuleContext(u, 1, null, 0))).isEmpty();
  }

  @Test
  void synonymWithoutAcceptedRuleAppliesToMisappliedToo() {
    NameUsage u = usage();
    u.setStatus(Status.MISAPPLIED);

    assertThat(new SynonymWithoutAcceptedRule().evaluate(new RuleContext(u, 0, null, 0))).isPresent();
  }

  @Test
  void synonymWithoutAcceptedRuleDoesNotApplyToAcceptedUsages() {
    NameUsage u = usage();
    u.setStatus(Status.ACCEPTED);

    assertThat(new SynonymWithoutAcceptedRule().evaluate(new RuleContext(u, 0, null, 0))).isEmpty();
  }

  // --- MissingPublishedInRule ---

  @Test
  void missingPublishedInRuleFindsWhenNull() {
    NameUsage u = usage();
    u.setPublishedInReferenceId(null);

    assertThat(new MissingPublishedInRule().evaluate(new RuleContext(u, 0, null, 0))).isPresent();
  }

  @Test
  void missingPublishedInRuleOkWhenSet() {
    NameUsage u = usage();
    u.setPublishedInReferenceId(42);

    assertThat(new MissingPublishedInRule().evaluate(new RuleContext(u, 0, null, 0))).isEmpty();
  }

  // --- DuplicateNameRule ---

  @Test
  void duplicateNameRuleOkWhenZero() {
    assertThat(new DuplicateNameRule().evaluate(new RuleContext(usage(), 0, null, 0))).isEmpty();
  }

  @Test
  void duplicateNameRuleFindsWhenPositive() {
    Optional<Finding> finding = new DuplicateNameRule().evaluate(new RuleContext(usage(), 0, null, 2));

    assertThat(finding).isPresent();
    assertThat(finding.get().severity()).isEqualTo(Severity.WARNING);
    assertThat(finding.get().context()).isEqualTo(Map.of("count", 2));
  }

  // --- YearVsReferenceRule ---

  @Test
  void yearVsReferenceRuleOkWhenDiffIsExactlyTwo() {
    NameUsage u = usage();
    u.setPublishedInYear(1980);

    assertThat(new YearVsReferenceRule().evaluate(new RuleContext(u, 0, referenceIssued("1978"), 0)))
        .isEmpty();
  }

  @Test
  void yearVsReferenceRuleFindsWhenDiffIsThree() {
    NameUsage u = usage();
    u.setPublishedInYear(1981);

    Optional<Finding> finding = new YearVsReferenceRule()
        .evaluate(new RuleContext(u, 0, referenceIssued("1978"), 0));
    assertThat(finding).isPresent();
    assertThat(finding.get().context()).isEqualTo(Map.of("year", 1981, "referenceYear", 1978));
  }

  @Test
  void yearVsReferenceRuleOkWhenNoPublishedInReference() {
    NameUsage u = usage();
    u.setPublishedInYear(1981);

    assertThat(new YearVsReferenceRule().evaluate(new RuleContext(u, 0, null, 0))).isEmpty();
  }

  @Test
  void yearVsReferenceRuleOkWhenNoPublishedInYear() {
    NameUsage u = usage(); // publishedInYear never set

    assertThat(new YearVsReferenceRule().evaluate(new RuleContext(u, 0, referenceIssued("1978"), 0)))
        .isEmpty();
  }

  @Test
  void yearVsReferenceRuleOkWhenReferenceYearUnextractable() {
    NameUsage u = usage();
    u.setPublishedInYear(1981);

    assertThat(new YearVsReferenceRule().evaluate(new RuleContext(u, 0, referenceIssued("undated"), 0)))
        .isEmpty();
  }

  // --- GenusMismatchRule ---

  @Test
  void genusMismatchFlagsWhenGenusDiffersFromClassification() {
    NameUsage u = new NameUsage();
    u.setStatus(Status.ACCEPTED);
    u.setScientificName("Aus bus");
    u.setGenus("Aus");

    Optional<Finding> finding =
        new GenusMismatchRule().evaluate(new RuleContext(u, 0, null, 0, "Bus"));
    assertThat(finding).isPresent();
    assertThat(finding.get().rule()).isEqualTo("genus_mismatch");
    assertThat(finding.get().severity()).isEqualTo(Severity.WARNING);
    assertThat(finding.get().message()).contains("Aus").contains("Bus");
  }

  @Test
  void genusMismatchOkWhenGenusMatchesOrNoAncestorGenus() {
    NameUsage u = new NameUsage();
    u.setStatus(Status.ACCEPTED);
    u.setGenus("Aus");

    assertThat(new GenusMismatchRule().evaluate(new RuleContext(u, 0, null, 0, "Aus"))).isEmpty();
    // case-insensitive
    assertThat(new GenusMismatchRule().evaluate(new RuleContext(u, 0, null, 0, "aus"))).isEmpty();
    // no ancestor genus in the classification -> nothing to compare against
    assertThat(new GenusMismatchRule().evaluate(new RuleContext(u, 0, null, 0, null))).isEmpty();
  }

  // --- RankVsParentRule ---

  @Test
  void rankVsParentFlagsWhenNotBelowParent() {
    NameUsage u = new NameUsage();
    u.setStatus(Status.ACCEPTED);
    u.setRank("genus"); // a genus parented under a species is wrong
    RuleContext ctx = new RuleContext(u, 0, null, 0, null, "species", null, null, 0);
    assertThat(new RankVsParentRule().evaluate(ctx)).isPresent();
  }

  @Test
  void rankVsParentOkWhenBelowParentOrNoParent() {
    NameUsage u = new NameUsage();
    u.setRank("species");
    assertThat(new RankVsParentRule().evaluate(new RuleContext(u, 0, null, 0, null, "genus", null, null, 0)))
        .isEmpty();
    // no parent -> nothing to compare
    assertThat(new RankVsParentRule().evaluate(new RuleContext(u, 0, null, 0))).isEmpty();
  }

  // --- SpeciesEpithetMismatchRule ---

  @Test
  void speciesEpithetMismatchFlags() {
    NameUsage u = new NameUsage();
    u.setStatus(Status.ACCEPTED);
    u.setSpecificEpithet("tigris");
    RuleContext ctx = new RuleContext(u, 0, null, 0, null, null, null, "leo", 0);
    assertThat(new SpeciesEpithetMismatchRule().evaluate(ctx)).isPresent();
  }

  @Test
  void speciesEpithetMismatchOkWhenMatchesOrNoAncestor() {
    NameUsage u = new NameUsage();
    u.setStatus(Status.ACCEPTED);
    u.setSpecificEpithet("leo");
    assertThat(new SpeciesEpithetMismatchRule()
        .evaluate(new RuleContext(u, 0, null, 0, null, null, null, "leo", 0))).isEmpty();
    assertThat(new SpeciesEpithetMismatchRule()
        .evaluate(new RuleContext(u, 0, null, 0, null, null, null, null, 0))).isEmpty();
  }

  // --- GenusYearAfterSpeciesRule ---

  @Test
  void genusYearAfterSpeciesFlagsWhenGenusYounger() {
    NameUsage u = new NameUsage();
    u.setStatus(Status.ACCEPTED);
    u.setPublishedInYear(1758);
    RuleContext ctx = new RuleContext(u, 0, null, 0, null, null, 1816, null, 0);
    assertThat(new GenusYearAfterSpeciesRule().evaluate(ctx)).isPresent();
  }

  @Test
  void genusYearAfterSpeciesOkWhenGenusOlderOrMissing() {
    NameUsage u = new NameUsage();
    u.setStatus(Status.ACCEPTED);
    u.setPublishedInYear(1816);
    assertThat(new GenusYearAfterSpeciesRule()
        .evaluate(new RuleContext(u, 0, null, 0, null, null, 1758, null, 0))).isEmpty();
    assertThat(new GenusYearAfterSpeciesRule()
        .evaluate(new RuleContext(u, 0, null, 0, null, null, null, null, 0))).isEmpty();
  }

  // --- SynonymOfNonAcceptedRule ---

  @Test
  void synonymOfNonAcceptedFlags() {
    NameUsage u = new NameUsage();
    u.setStatus(Status.SYNONYM);
    RuleContext ctx = new RuleContext(u, 1, null, 0, null, null, null, null, 1);
    assertThat(new SynonymOfNonAcceptedRule().evaluate(ctx)).isPresent();
  }

  @Test
  void synonymOfNonAcceptedOkWhenAllAcceptedOrNotSynonym() {
    NameUsage syn = new NameUsage();
    syn.setStatus(Status.SYNONYM);
    assertThat(new SynonymOfNonAcceptedRule()
        .evaluate(new RuleContext(syn, 1, null, 0, null, null, null, null, 0))).isEmpty();
    NameUsage acc = new NameUsage();
    acc.setStatus(Status.ACCEPTED);
    assertThat(new SynonymOfNonAcceptedRule()
        .evaluate(new RuleContext(acc, 0, null, 0, null, null, null, null, 1))).isEmpty();
  }

  // --- DanglingReferenceRule ---

  private static RuleContext ctxDangling(int danglingRefs) {
    return new RuleContext(usage(), 0, null, 0, null, null, null, null, 0, false, danglingRefs);
  }

  @Test
  void danglingReferenceRuleFlagsMissingTaxonomicReferences() {
    Optional<Finding> finding = new DanglingReferenceRule().evaluate(ctxDangling(2));
    assertThat(finding).isPresent();
    assertThat(finding.get().rule()).isEqualTo("dangling_reference");
    assertThat(finding.get().severity()).isEqualTo(Severity.ERROR);
  }

  @Test
  void danglingReferenceRuleQuietWhenNoneDangle() {
    assertThat(new DanglingReferenceRule().evaluate(ctxDangling(0))).isEmpty();
  }

  // --- InfraspecificMissingSpeciesRule ---

  private static NameUsage infraspecific() {
    NameUsage u = new NameUsage();
    u.setScientificName("Panthera leo persica");
    u.setRank("subspecies");
    u.setInfraspecificEpithet("persica");
    u.setStatus(Status.ACCEPTED);
    return u;
  }

  private static RuleContext ctxInfra(NameUsage u, boolean hasSpeciesAncestor) {
    return new RuleContext(u, 0, null, 0, null, null, null, null, 0, hasSpeciesAncestor, 0);
  }

  @Test
  void infraspecificMissingSpeciesFlagsWhenNoSpeciesAncestor() {
    Optional<Finding> finding = new InfraspecificMissingSpeciesRule().evaluate(ctxInfra(infraspecific(), false));
    assertThat(finding).isPresent();
    assertThat(finding.get().rule()).isEqualTo("infraspecific_missing_species");
    assertThat(finding.get().severity()).isEqualTo(Severity.WARNING);
  }

  @Test
  void infraspecificMissingSpeciesQuietWhenSpeciesAncestorPresent() {
    assertThat(new InfraspecificMissingSpeciesRule().evaluate(ctxInfra(infraspecific(), true))).isEmpty();
  }

  @Test
  void infraspecificMissingSpeciesIgnoresSpeciesAndHigherRanks() {
    // A species has no infraspecific epithet -> never flagged, even at the top of the tree.
    NameUsage species = usage(); // "Abies alba", no infraspecificEpithet
    assertThat(new InfraspecificMissingSpeciesRule().evaluate(ctxInfra(species, false))).isEmpty();
  }

  @Test
  void infraspecificMissingSpeciesIgnoresNonAcceptedUsages() {
    // Only accepted usages sit in the classification tree; a synonym isn't parented under a species.
    NameUsage syn = infraspecific();
    syn.setStatus(Status.SYNONYM);
    assertThat(new InfraspecificMissingSpeciesRule().evaluate(ctxInfra(syn, false))).isEmpty();
  }

  // --- New CLB-issue-derived rules (2026-07-24). Pure functions of the usage, so a bare 4-arg ctx. ---

  private static RuleContext ctx(NameUsage u) {
    return new RuleContext(u, 0, null, 0);
  }

  // --- UppercaseEpithetRule (CLB UPPERCASE_EPITHET) ---

  @Test
  void uppercaseEpithetFlagsACapitalInAnEpithet() {
    NameUsage u = new NameUsage();
    u.setScientificName("Panthera Leo");
    u.setRank("species");
    u.setSpecificEpithet("Leo");
    Optional<Finding> f = new UppercaseEpithetRule().evaluate(ctx(u));
    assertThat(f).isPresent();
    assertThat(f.get().rule()).isEqualTo("uppercase_epithet");
    assertThat(f.get().severity()).isEqualTo(Severity.WARNING);
  }

  @Test
  void uppercaseEpithetQuietForLowercaseEpithetsAndNonScientificNames() {
    NameUsage ok = new NameUsage();
    ok.setRank("species");
    ok.setSpecificEpithet("leo");
    assertThat(new UppercaseEpithetRule().evaluate(ctx(ok))).isEmpty();
    // a formula / non-scientific name is skipped even with odd casing
    NameUsage formula = new NameUsage();
    formula.setNameType(NameType.FORMULA);
    formula.setSpecificEpithet("Leo");
    assertThat(new UppercaseEpithetRule().evaluate(ctx(formula))).isEmpty();
  }

  // --- MissingGenusRule (CLB MISSING_GENUS) ---

  @Test
  void missingGenusFlagsABinomialWithoutAGenus() {
    NameUsage u = new NameUsage();
    u.setRank("species");
    u.setSpecificEpithet("leo");
    u.setGenus(null);
    Optional<Finding> f = new MissingGenusRule().evaluate(ctx(u));
    assertThat(f).isPresent();
    assertThat(f.get().rule()).isEqualTo("missing_genus");

    NameUsage ok = new NameUsage();
    ok.setRank("species");
    ok.setSpecificEpithet("leo");
    ok.setGenus("Panthera");
    assertThat(new MissingGenusRule().evaluate(ctx(ok))).isEmpty();
  }

  // --- BinomialAboveGenusRule (CLB HIGHER_RANK_BINOMIAL) ---

  @Test
  void binomialAboveGenusFlagsASpeciesEpithetOnAGenusOrHigher() {
    NameUsage genusWithEpithet = new NameUsage();
    genusWithEpithet.setRank("genus");
    genusWithEpithet.setSpecificEpithet("leo");
    Optional<Finding> f = new BinomialAboveGenusRule().evaluate(ctx(genusWithEpithet));
    assertThat(f).isPresent();
    assertThat(f.get().rule()).isEqualTo("binomial_above_genus");

    NameUsage species = new NameUsage();
    species.setRank("species");
    species.setSpecificEpithet("leo");
    assertThat(new BinomialAboveGenusRule().evaluate(ctx(species))).isEmpty();
  }

  // --- GenderNotApplicableRule (CLB GENDER_*_NOT_APPLICABLE) ---

  @Test
  void genderNotApplicableFlagsGenderOffAGenusAndAgreementOffABinomial() {
    // gender set on a species (belongs to the genus)
    NameUsage speciesWithGender = new NameUsage();
    speciesWithGender.setRank("species");
    speciesWithGender.setSpecificEpithet("leo");
    speciesWithGender.setGender(Gender.FEMININE);
    assertThat(new GenderNotApplicableRule().evaluate(ctx(speciesWithGender))).isPresent();

    // genderAgreement set on a genus (only meaningful for a bi/trinomial)
    NameUsage genusWithAgreement = new NameUsage();
    genusWithAgreement.setRank("genus");
    genusWithAgreement.setGenderAgreement(true);
    assertThat(new GenderNotApplicableRule().evaluate(ctx(genusWithAgreement))).isPresent();
  }

  @Test
  void genderNotApplicableQuietWhenGenderOnGenusAndAgreementOnSpecies() {
    NameUsage genus = new NameUsage();
    genus.setRank("genus");
    genus.setGender(Gender.FEMININE);
    assertThat(new GenderNotApplicableRule().evaluate(ctx(genus))).isEmpty();

    NameUsage species = new NameUsage();
    species.setRank("species");
    species.setSpecificEpithet("leo");
    species.setGenderAgreement(true);
    assertThat(new GenderNotApplicableRule().evaluate(ctx(species))).isEmpty();
  }

  // --- SuperfluousAuthorshipRule (CLB SUPERFLUOUS_AUTHORSHIP) ---

  @Test
  void superfluousAuthorshipFlagsAnAutonymCarryingAnAuthor() {
    NameUsage autonym = new NameUsage(); // "Panthera leo leo" -- infra == specific epithet
    autonym.setRank("subspecies");
    autonym.setSpecificEpithet("leo");
    autonym.setInfraspecificEpithet("leo");
    autonym.setAuthorship("Linnaeus, 1758");
    Optional<Finding> f = new SuperfluousAuthorshipRule().evaluate(ctx(autonym));
    assertThat(f).isPresent();
    assertThat(f.get().rule()).isEqualTo("superfluous_authorship");
  }

  @Test
  void superfluousAuthorshipQuietForAutonymWithoutAuthorAndForNonAutonyms() {
    NameUsage autonymNoAuthor = new NameUsage();
    autonymNoAuthor.setRank("subspecies");
    autonymNoAuthor.setSpecificEpithet("leo");
    autonymNoAuthor.setInfraspecificEpithet("leo");
    assertThat(new SuperfluousAuthorshipRule().evaluate(ctx(autonymNoAuthor))).isEmpty();

    NameUsage normalSubsp = new NameUsage(); // "Panthera leo persica" -- not an autonym
    normalSubsp.setRank("subspecies");
    normalSubsp.setSpecificEpithet("leo");
    normalSubsp.setInfraspecificEpithet("persica");
    normalSubsp.setAuthorship("Meyer, 1826");
    assertThat(new SuperfluousAuthorshipRule().evaluate(ctx(normalSubsp))).isEmpty();
  }

  // --- SuspiciousNameCharactersRule (CLB HOMOGLYPH_CHARACTERS / DIACRITIC_CHARACTERS) ---

  @Test
  void suspiciousCharactersFlagsAHomoglyphInTheName() {
    NameUsage u = new NameUsage();
    u.setScientificName("Pаnthera leo"); // Cyrillic 'а' (U+0430) masquerading as Latin 'a'
    Optional<Finding> f = new SuspiciousNameCharactersRule().evaluate(ctx(u));
    assertThat(f).isPresent();
    assertThat(f.get().rule()).isEqualTo("suspicious_name_characters");
  }

  @Test
  void suspiciousCharactersQuietForACleanAsciiName() {
    NameUsage u = new NameUsage();
    u.setScientificName("Panthera leo");
    assertThat(new SuspiciousNameCharactersRule().evaluate(ctx(u))).isEmpty();
  }

  // --- DuplicateChildRecordsRule (CLB DUPLICATE_DISTRIBUTIONS / _VERNACULAR_NAMES / ...) ---

  private static RuleContext ctxDuplicateChildren(Set<String> types) {
    return new RuleContext(usage(), 0, null, 0, null, null, null, null, 0, false, 0, types, false);
  }

  @Test
  void duplicateChildRecordsFlagsAndNamesEachDuplicatedType() {
    Optional<Finding> f =
        new DuplicateChildRecordsRule().evaluate(ctxDuplicateChildren(Set.of("distribution", "vernacular")));
    assertThat(f).isPresent();
    assertThat(f.get().rule()).isEqualTo("duplicate_child_records");
    assertThat(f.get().severity()).isEqualTo(Severity.WARNING);
    assertThat(f.get().message()).contains("distribution").contains("vernacular");
  }

  @Test
  void duplicateChildRecordsQuietWhenNoDuplicates() {
    assertThat(new DuplicateChildRecordsRule().evaluate(ctxDuplicateChildren(Set.of()))).isEmpty();
    assertThat(new DuplicateChildRecordsRule().evaluate(ctx(usage()))).isEmpty();
  }

  // --- SynonymRankDiffersRule (CLB SYNONYM_RANK_DIFFERS) ---

  private static RuleContext ctxSynonymRankDiffers(boolean differs) {
    return new RuleContext(usage(), 1, null, 0, null, null, null, null, 0, false, 0, Set.of(), differs);
  }

  @Test
  void synonymRankDiffersFlagsWhenRanksDiffer() {
    Optional<Finding> f = new SynonymRankDiffersRule().evaluate(ctxSynonymRankDiffers(true));
    assertThat(f).isPresent();
    assertThat(f.get().rule()).isEqualTo("synonym_rank_differs");
    assertThat(f.get().severity()).isEqualTo(Severity.WARNING);
  }

  @Test
  void synonymRankDiffersQuietWhenRanksMatch() {
    assertThat(new SynonymRankDiffersRule().evaluate(ctxSynonymRankDiffers(false))).isEmpty();
  }

  // --- LinkedGenusSpellingRule (genus_link_spelling_mismatch) ---

  private static RuleContext ctxLinkedGenus(String genusToken, String linkedGenusName) {
    NameUsage u = usage();
    u.setGenus(genusToken);
    return new RuleContext(u, 0, null, 0, null, null, null, null, 0, false, 0, Set.of(), false,
        linkedGenusName);
  }

  @Test
  void linkedGenusSpellingFlagsAMismatch() {
    Optional<Finding> f = new LinkedGenusSpellingRule().evaluate(ctxLinkedGenus("Abies", "Pinus"));
    assertThat(f).isPresent();
    assertThat(f.get().rule()).isEqualTo("genus_link_spelling_mismatch");
    assertThat(f.get().message()).contains("Pinus").contains("Abies");
  }

  @Test
  void linkedGenusSpellingQuietWhenMatchingOrUnlinked() {
    assertThat(new LinkedGenusSpellingRule().evaluate(ctxLinkedGenus("Abies", "Abies"))).isEmpty();
    // unlinked (null linked name) -> not this rule's concern
    assertThat(new LinkedGenusSpellingRule().evaluate(ctxLinkedGenus("Abies", null))).isEmpty();
  }

  // --- AcceptedGenusLinkRule (accepted_genus_link_not_classification) ---

  private static RuleContext ctxAcceptedGenusLink(Integer genusId, Integer ancestorGenusId) {
    NameUsage u = usage(); // ACCEPTED
    u.setGenusId(genusId);
    return new RuleContext(u, 0, null, 0, null, null, null, null, 0, false, 0, Set.of(), false, null,
        ancestorGenusId);
  }

  @Test
  void acceptedGenusLinkFlagsWhenLinkedGenusIsNotTheClassificationGenus() {
    Optional<Finding> f = new AcceptedGenusLinkRule().evaluate(ctxAcceptedGenusLink(5, 6));
    assertThat(f).isPresent();
    assertThat(f.get().rule()).isEqualTo("accepted_genus_link_not_classification");
  }

  @Test
  void acceptedGenusLinkQuietWhenMatchingOrUnlinked() {
    assertThat(new AcceptedGenusLinkRule().evaluate(ctxAcceptedGenusLink(5, 5))).isEmpty(); // same genus
    assertThat(new AcceptedGenusLinkRule().evaluate(ctxAcceptedGenusLink(null, 6))).isEmpty(); // unlinked
    assertThat(new AcceptedGenusLinkRule().evaluate(ctxAcceptedGenusLink(5, null))).isEmpty(); // no ancestor
  }
}
