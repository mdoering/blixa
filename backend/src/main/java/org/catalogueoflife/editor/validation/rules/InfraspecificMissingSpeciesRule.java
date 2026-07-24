package org.catalogueoflife.editor.validation.rules;

import java.util.Optional;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.validation.Finding;
import org.catalogueoflife.editor.validation.RuleContext;
import org.catalogueoflife.editor.validation.Severity;
import org.catalogueoflife.editor.validation.ValidationRule;
import org.springframework.stereotype.Component;

// An accepted infraspecific name (subspecies/variety/form -- carries an infraspecific epithet) must
// sit under a species in the classification. Flags the structural error of one parented straight
// under a genus (or higher), skipping the species level -- which SpeciesEpithetMismatchRule can't
// catch, since with no species ancestor there is nothing to compare the epithet against. Only
// accepted usages are in the tree; synonyms aren't parented under a species, so they're skipped.
@Component
public class InfraspecificMissingSpeciesRule implements ValidationRule {

  @Override
  public String key() {
    return "infraspecific_missing_species";
  }

  @Override
  public Severity severity() {
    return Severity.WARNING;
  }

  @Override
  public Optional<Finding> evaluate(RuleContext ctx) {
    NameUsage u = ctx.usage();
    String infra = u.getInfraspecificEpithet();
    if (u.getStatus() == Status.ACCEPTED && infra != null && !infra.isBlank()
        && !ctx.hasSpeciesAncestor()) {
      return Optional.of(new Finding(key(), severity(),
          "infraspecific name has no species ancestor in its classification -- it should sit under a species",
          null));
    }
    return Optional.empty();
  }
}
