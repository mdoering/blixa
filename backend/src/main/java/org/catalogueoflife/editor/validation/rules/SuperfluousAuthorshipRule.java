package org.catalogueoflife.editor.validation.rules;

import java.util.Optional;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.validation.Finding;
import org.catalogueoflife.editor.validation.RuleContext;
import org.catalogueoflife.editor.validation.Severity;
import org.catalogueoflife.editor.validation.ValidationRule;
import org.springframework.stereotype.Component;

// An autonym -- a nominotypical infraspecific name whose infraspecific epithet repeats the specific
// epithet (e.g. "Panthera leo leo") -- must not carry its own authorship: it takes the species'
// author implicitly. Flags an autonym with a non-blank authorship (CLB SUPERFLUOUS_AUTHORSHIP; this
// also subsumes NOMINOTYPICAL_AUTHORSHIP_DIFFERS, since any explicit author on an autonym is wrong).
@Component
public class SuperfluousAuthorshipRule implements ValidationRule {

  @Override
  public String key() {
    return "superfluous_authorship";
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
    String spec = u.getSpecificEpithet();
    String infra = u.getInfraspecificEpithet();
    boolean autonym = spec != null && infra != null && spec.equalsIgnoreCase(infra);
    boolean hasAuthorship = u.getAuthorship() != null && !u.getAuthorship().isBlank();
    if (autonym && hasAuthorship) {
      return Optional.of(new Finding(key(), severity(),
          "autonym carries its own authorship '" + u.getAuthorship()
              + "' -- an autonym takes the species' author implicitly",
          null));
    }
    return Optional.empty();
  }
}
