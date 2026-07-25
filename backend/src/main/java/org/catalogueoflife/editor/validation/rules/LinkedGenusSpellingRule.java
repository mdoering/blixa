package org.catalogueoflife.editor.validation.rules;

import java.util.Optional;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.validation.Finding;
import org.catalogueoflife.editor.validation.RuleContext;
import org.catalogueoflife.editor.validation.Severity;
import org.catalogueoflife.editor.validation.ValidationRule;
import org.springframework.stereotype.Component;

// A binomial pinned to a nomenclatural genus (genus_id) whose name doesn't EXACTLY match the name's
// own parsed genus token -- a mis-link (linked to the wrong genus) or drift (the linked genus was
// renamed while the binomial's genus token wasn't). Only fires when a link exists; an unlinked
// binomial is not this rule's concern. Case-sensitive exact comparison.
@Component
public class LinkedGenusSpellingRule implements ValidationRule {

  @Override
  public String key() {
    return "genus_link_spelling_mismatch";
  }

  @Override
  public Severity severity() {
    return Severity.WARNING;
  }

  @Override
  public Optional<Finding> evaluate(RuleContext ctx) {
    NameUsage u = ctx.usage();
    String linked = ctx.linkedGenusName();
    String genusToken = u.getGenus();
    if (linked == null || genusToken == null || genusToken.isBlank()) {
      return Optional.empty(); // unlinked, or no genus token -- nothing to compare
    }
    if (!linked.equals(genusToken)) {
      return Optional.of(new Finding(key(), severity(),
          "the linked genus '" + linked + "' differs from the name's genus '" + genusToken + "'", null));
    }
    return Optional.empty();
  }
}
