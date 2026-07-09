package org.catalogueoflife.editor.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.Reference;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.validation.rules.DuplicateNameRule;
import org.catalogueoflife.editor.validation.rules.GenusMismatchRule;
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
}
