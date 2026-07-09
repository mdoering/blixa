package org.catalogueoflife.editor.validation.rules;

import java.util.Optional;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.validation.Finding;
import org.catalogueoflife.editor.validation.RuleContext;
import org.catalogueoflife.editor.validation.Severity;
import org.catalogueoflife.editor.validation.ValidationRule;
import org.springframework.stereotype.Component;

// The species-level companion to GenusMismatchRule: an infraspecific name (subspecies/variety/...)
// whose specific epithet differs from the species it sits under in the classification. Only accepted
// usages are in the tree; a species has no species ancestor so it is never flagged here.
@Component
public class SpeciesEpithetMismatchRule implements ValidationRule {

  @Override
  public String key() {
    return "species_epithet_mismatch";
  }

  @Override
  public Severity severity() {
    return Severity.WARNING;
  }

  @Override
  public Optional<Finding> evaluate(RuleContext ctx) {
    NameUsage u = ctx.usage();
    String epithet = u.getSpecificEpithet();
    String ancestorEpithet = ctx.ancestorSpeciesEpithet();
    if (u.getStatus() == Status.ACCEPTED && epithet != null && ancestorEpithet != null
        && !epithet.equalsIgnoreCase(ancestorEpithet)) {
      return Optional.of(new Finding(key(), severity(),
          "specific epithet '" + epithet + "' differs from the species '" + ancestorEpithet
              + "' it sits under",
          null));
    }
    return Optional.empty();
  }
}
