package org.catalogueoflife.editor.validation.rules;

import java.util.Optional;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.validation.Finding;
import org.catalogueoflife.editor.validation.RuleContext;
import org.catalogueoflife.editor.validation.Severity;
import org.catalogueoflife.editor.validation.ValidationRule;
import org.springframework.stereotype.Component;

// An epithet (specific or infraspecific) containing an upper-case letter -- epithets are always
// lower-cased in both codes (CLB's UPPERCASE_EPITHET). Cultivar epithets are excluded (they are
// legitimately capitalised). Non-scientific names (formula/informal/...) are skipped.
@Component
public class UppercaseEpithetRule implements ValidationRule {

  @Override
  public String key() {
    return "uppercase_epithet";
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
    String bad = firstWithUppercase(u.getSpecificEpithet(), u.getInfraspecificEpithet());
    if (bad != null) {
      return Optional.of(new Finding(key(), severity(),
          "epithet '" + bad + "' contains an upper-case letter (epithets are lower-cased)", null));
    }
    return Optional.empty();
  }

  private static String firstWithUppercase(String... epithets) {
    for (String e : epithets) {
      if (e != null && e.chars().anyMatch(Character::isUpperCase)) {
        return e;
      }
    }
    return null;
  }
}
