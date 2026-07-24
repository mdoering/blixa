package org.catalogueoflife.editor.validation.rules;

import java.util.Optional;
import org.catalogueoflife.editor.validation.Finding;
import org.catalogueoflife.editor.validation.RuleContext;
import org.catalogueoflife.editor.validation.Severity;
import org.catalogueoflife.editor.validation.ValidationRule;
import org.springframework.stereotype.Component;

// A synonym normally shares the rank of the accepted name it points to. This flags a synonym whose
// rank differs from an accepted target's (CLB SYNONYM_RANK_DIFFERS) -- often a mis-linked synonym or
// a rank typo. The comparison (both ranks required non-null) lives in
// NameUsageMapper.synonymRankDiffers; a missing rank on either side is a separate concern, not a
// rank *mismatch*, so it is not reported here.
@Component
public class SynonymRankDiffersRule implements ValidationRule {

  @Override
  public String key() {
    return "synonym_rank_differs";
  }

  @Override
  public Severity severity() {
    return Severity.WARNING;
  }

  @Override
  public Optional<Finding> evaluate(RuleContext ctx) {
    if (ctx.synonymRankDiffers()) {
      return Optional.of(new Finding(key(), severity(),
          "this synonym's rank differs from the rank of its accepted name", null));
    }
    return Optional.empty();
  }
}
