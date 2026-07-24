package org.catalogueoflife.editor.validation.rules;

import java.util.Optional;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.validation.Finding;
import org.catalogueoflife.editor.validation.RuleContext;
import org.catalogueoflife.editor.validation.Severity;
import org.catalogueoflife.editor.validation.ValidationRule;
import org.springframework.stereotype.Component;

// A bi/trinomial (has a specific epithet) whose genus part is missing (CLB MISSING_GENUS) -- the
// name can't be a valid species name without one. Non-scientific names are skipped.
@Component
public class MissingGenusRule implements ValidationRule {

  @Override
  public String key() {
    return "missing_genus";
  }

  @Override
  public Severity severity() {
    return Severity.WARNING;
  }

  @Override
  public Optional<Finding> evaluate(RuleContext ctx) {
    NameUsage u = ctx.usage();
    if (NameTypes.isNonScientific(u.getNameType())) {
      return Optional.empty();
    }
    boolean hasEpithet = u.getSpecificEpithet() != null && !u.getSpecificEpithet().isBlank();
    boolean hasGenus = u.getGenus() != null && !u.getGenus().isBlank();
    if (hasEpithet && !hasGenus) {
      return Optional.of(new Finding(key(), severity(),
          "a species-level name has a specific epithet but no genus", null));
    }
    return Optional.empty();
  }
}
