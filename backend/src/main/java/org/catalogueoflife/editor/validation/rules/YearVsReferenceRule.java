package org.catalogueoflife.editor.validation.rules;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.catalogueoflife.editor.name.Reference;
import org.catalogueoflife.editor.validation.Finding;
import org.catalogueoflife.editor.validation.RuleContext;
import org.catalogueoflife.editor.validation.Severity;
import org.catalogueoflife.editor.validation.ValidationRule;
import org.springframework.stereotype.Component;

// features.md rule: a name's recorded publication year should roughly agree with the year of its
// published_in reference. Reference.issued has no numeric year field -- it's a free-text citation
// date (see name/Reference.java) -- so the year is extracted with a plain \d{4} regex, good enough
// for the common "1978", "1978-05", "May 1978" shapes; when the issued text has no 4-digit run at
// all (or is missing), the check is simply skipped rather than guessing.
@Component
public class YearVsReferenceRule implements ValidationRule {

  private static final Pattern YEAR = Pattern.compile("\\d{4}");
  private static final int MAX_DIFF = 2;

  @Override
  public String key() {
    return "year_vs_reference";
  }

  @Override
  public Severity severity() {
    return Severity.WARNING;
  }

  @Override
  public Optional<Finding> evaluate(RuleContext ctx) {
    Integer year = ctx.usage().getPublishedInYear();
    Reference ref = ctx.publishedInReference();
    if (year == null || ref == null) {
      return Optional.empty();
    }
    Integer referenceYear = extractYear(ref.getIssued());
    if (referenceYear == null || Math.abs(year - referenceYear) <= MAX_DIFF) {
      return Optional.empty();
    }
    return Optional.of(new Finding(key(), severity(),
        "authorship year " + year + " differs from reference year " + referenceYear,
        Map.of("year", year, "referenceYear", referenceYear)));
  }

  private static Integer extractYear(String issued) {
    if (issued == null) {
      return null;
    }
    Matcher m = YEAR.matcher(issued);
    return m.find() ? Integer.valueOf(m.group()) : null;
  }
}
