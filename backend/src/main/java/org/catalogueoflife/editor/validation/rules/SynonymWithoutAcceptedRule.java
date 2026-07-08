package org.catalogueoflife.editor.validation.rules;

import java.util.Optional;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.validation.Finding;
import org.catalogueoflife.editor.validation.RuleContext;
import org.catalogueoflife.editor.validation.Severity;
import org.catalogueoflife.editor.validation.ValidationRule;
import org.springframework.stereotype.Component;

// features.md: every synonym/misapplied name must point to at least one accepted taxon via
// synonym_accepted (see name/SynonymAcceptedMapper.java); a zero synonymAcceptedCount here means
// the usage is orphaned in the synonymy -- an ERROR, not just a warning, since it's a genuine
// taxonomic-integrity gap.
@Component
public class SynonymWithoutAcceptedRule implements ValidationRule {

  @Override
  public String key() {
    return "synonym_without_accepted";
  }

  @Override
  public Severity severity() {
    return Severity.ERROR;
  }

  @Override
  public Optional<Finding> evaluate(RuleContext ctx) {
    Status status = ctx.usage().getStatus();
    boolean isSynonymLike = status == Status.SYNONYM || status == Status.MISAPPLIED;
    int acceptedCount = ctx.synonymAcceptedCount() == null ? 0 : ctx.synonymAcceptedCount();
    if (isSynonymLike && acceptedCount == 0) {
      return Optional.of(new Finding(key(), severity(),
          "synonym/misapplied name is not linked to any accepted taxon", null));
    }
    return Optional.empty();
  }
}
