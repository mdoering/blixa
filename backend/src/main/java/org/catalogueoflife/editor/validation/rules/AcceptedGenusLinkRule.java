package org.catalogueoflife.editor.validation.rules;

import java.util.Objects;
import java.util.Optional;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.validation.Finding;
import org.catalogueoflife.editor.validation.RuleContext;
import org.catalogueoflife.editor.validation.Severity;
import org.catalogueoflife.editor.validation.ValidationRule;
import org.springframework.stereotype.Component;

// An ACCEPTED binomial's nomenclatural genus (genus_id) must be the very genus usage it sits under in
// the classification -- for an accepted name the two are the same genus. Flags, by id (so a
// homonymous genus of the same name doesn't hide it), when a linked accepted name's genus_id differs
// from its classification-ancestor genus: a mis-link, or drift after the taxon was moved in the tree.
// Only fires for accepted usages that are actually linked and do sit under a genus. The name-level
// counterpart (token vs classification) is genus_mismatch.
@Component
public class AcceptedGenusLinkRule implements ValidationRule {

  @Override
  public String key() {
    return "accepted_genus_link_not_classification";
  }

  @Override
  public Severity severity() {
    return Severity.WARNING;
  }

  @Override
  public Optional<Finding> evaluate(RuleContext ctx) {
    NameUsage u = ctx.usage();
    if (u.getStatus() != Status.ACCEPTED || u.getGenusId() == null || ctx.ancestorGenusId() == null) {
      return Optional.empty();
    }
    if (!Objects.equals(u.getGenusId(), ctx.ancestorGenusId())) {
      return Optional.of(new Finding(key(), severity(),
          "the linked nomenclatural genus is not the genus this accepted name is classified under", null));
    }
    return Optional.empty();
  }
}
