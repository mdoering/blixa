package org.catalogueoflife.editor.validation.rules;

import java.util.Optional;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.validation.Finding;
import org.catalogueoflife.editor.validation.RuleContext;
import org.catalogueoflife.editor.validation.Severity;
import org.catalogueoflife.editor.validation.ValidationRule;
import org.springframework.stereotype.Component;

// features.md: flag a species/infraspecific name whose genus (the parsed genus token of its name)
// differs from the genus it actually sits under in the classification -- a common inconsistency
// after reparenting children (e.g. the acc->syn demote workflow), where "Aus bus" ends up under the
// accepted genus "Bus". A WARNING, not an error: it's a data-quality signal, and the epithet may be
// deliberately retained pending a new combination. Only accepted usages are in the tree, so only
// they have an ancestor genus to compare against.
@Component
public class GenusMismatchRule implements ValidationRule {

  @Override
  public String key() {
    return "genus_mismatch";
  }

  @Override
  public Severity severity() {
    return Severity.WARNING;
  }

  @Override
  public Optional<Finding> evaluate(RuleContext ctx) {
    NameUsage u = ctx.usage();
    String genus = u.getGenus();
    String ancestorGenus = ctx.ancestorGenusName();
    if (u.getStatus() == Status.ACCEPTED && genus != null && ancestorGenus != null
        && !genus.equalsIgnoreCase(ancestorGenus)) {
      return Optional.of(new Finding(key(), severity(),
          "genus '" + genus + "' differs from the classification genus '" + ancestorGenus + "'", null));
    }
    return Optional.empty();
  }
}
