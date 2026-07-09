package org.catalogueoflife.editor.validation.rules;

import java.util.Optional;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.validation.Finding;
import org.catalogueoflife.editor.validation.RuleContext;
import org.catalogueoflife.editor.validation.Severity;
import org.catalogueoflife.editor.validation.ValidationRule;
import org.springframework.stereotype.Component;

// features.md: the year of a genus should equal or precede that of the names placed in it -- a genus
// can't be published after a species it contains. INFO (not a hard error): publication years are
// often approximate and the check uses publishedInYear, which may be unset or rough.
@Component
public class GenusYearAfterSpeciesRule implements ValidationRule {

  @Override
  public String key() {
    return "genus_year_after_species";
  }

  @Override
  public Severity severity() {
    return Severity.INFO;
  }

  @Override
  public Optional<Finding> evaluate(RuleContext ctx) {
    NameUsage u = ctx.usage();
    Integer year = u.getPublishedInYear();
    Integer genusYear = ctx.ancestorGenusYear();
    if (u.getStatus() == Status.ACCEPTED && year != null && genusYear != null && genusYear > year) {
      return Optional.of(new Finding(key(), severity(),
          "the genus was published in " + genusYear + ", after this name's year " + year, null));
    }
    return Optional.empty();
  }
}
