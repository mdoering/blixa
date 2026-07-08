package org.catalogueoflife.editor.validation.rules;

import java.util.Map;
import java.util.Optional;
import org.catalogueoflife.editor.validation.Finding;
import org.catalogueoflife.editor.validation.RuleContext;
import org.catalogueoflife.editor.validation.Severity;
import org.catalogueoflife.editor.validation.ValidationRule;
import org.springframework.stereotype.Component;

// ctx.duplicateCount() is precomputed by ValidationService (NameUsageMapper.countDuplicates: other
// usages in the project with the same scientificName + authorship, NULL-safe on authorship).
@Component
public class DuplicateNameRule implements ValidationRule {

  @Override
  public String key() {
    return "duplicate_name";
  }

  @Override
  public Severity severity() {
    return Severity.WARNING;
  }

  @Override
  public Optional<Finding> evaluate(RuleContext ctx) {
    int count = ctx.duplicateCount();
    if (count > 0) {
      return Optional.of(new Finding(key(), severity(),
          "duplicate scientific name + authorship in project (" + count + " other usages)",
          Map.of("count", count)));
    }
    return Optional.empty();
  }
}
