package org.catalogueoflife.editor.validation.rules;

import java.util.Optional;
import org.catalogueoflife.editor.validation.Finding;
import org.catalogueoflife.editor.validation.RuleContext;
import org.catalogueoflife.editor.validation.Severity;
import org.catalogueoflife.editor.validation.ValidationRule;
import org.springframework.stereotype.Component;

// A purely informational nudge: a usage without a published_in reference is common and valid
// (many names predate consistent citation), so this is INFO, not a warning/error.
@Component
public class MissingPublishedInRule implements ValidationRule {

  @Override
  public String key() {
    return "missing_published_in";
  }

  @Override
  public Severity severity() {
    return Severity.INFO;
  }

  @Override
  public Optional<Finding> evaluate(RuleContext ctx) {
    if (ctx.usage().getPublishedInReferenceId() == null) {
      return Optional.of(new Finding(key(), severity(), "no published_in reference set", null));
    }
    return Optional.empty();
  }
}
