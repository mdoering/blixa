package org.catalogueoflife.editor.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.Reference;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.validation.rules.DuplicateNameRule;
import org.catalogueoflife.editor.validation.rules.MissingPublishedInRule;
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
}
