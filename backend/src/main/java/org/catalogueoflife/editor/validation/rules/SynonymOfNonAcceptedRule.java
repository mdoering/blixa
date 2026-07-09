package org.catalogueoflife.editor.validation.rules;

import java.util.Optional;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.validation.Finding;
import org.catalogueoflife.editor.validation.RuleContext;
import org.catalogueoflife.editor.validation.Severity;
import org.catalogueoflife.editor.validation.ValidationRule;
import org.springframework.stereotype.Component;

// features.md: synonyms must point at ACCEPTED taxa (no synonym chaining). A synonym/misapplied name
// linked to a target that is itself a synonym/misapplied/unassessed name is an integrity error --
// exactly the breakage a demote can leave behind if a synonym's target is later demoted too.
@Component
public class SynonymOfNonAcceptedRule implements ValidationRule {

  @Override
  public String key() {
    return "synonym_of_non_accepted";
  }

  @Override
  public Severity severity() {
    return Severity.ERROR;
  }

  @Override
  public Optional<Finding> evaluate(RuleContext ctx) {
    Status status = ctx.usage().getStatus();
    boolean isSynonymLike = status == Status.SYNONYM || status == Status.MISAPPLIED;
    if (isSynonymLike && ctx.synonymNonAcceptedTargetCount() > 0) {
      return Optional.of(new Finding(key(), severity(),
          "linked as a synonym of a name that is not accepted", null));
    }
    return Optional.empty();
  }
}
