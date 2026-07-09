package org.catalogueoflife.editor.validation.rules;

import java.util.Locale;
import java.util.Optional;
import org.catalogueoflife.editor.validation.Finding;
import org.catalogueoflife.editor.validation.RuleContext;
import org.catalogueoflife.editor.validation.Severity;
import org.catalogueoflife.editor.validation.ValidationRule;
import org.gbif.nameparser.api.Rank;
import org.springframework.stereotype.Component;

// A usage's rank must sit strictly below its parent's in the classification (a species under a
// genus, a genus under a family, ...). Flags the inverse -- e.g. a genus parented under a species,
// or two same-rank names nested. Ranks that don't map to a comparable Rank (other/unranked) are
// skipped rather than guessed.
@Component
public class RankVsParentRule implements ValidationRule {

  @Override
  public String key() {
    return "rank_vs_parent";
  }

  @Override
  public Severity severity() {
    return Severity.WARNING;
  }

  @Override
  public Optional<Finding> evaluate(RuleContext ctx) {
    String parentRankStr = ctx.parentRank();
    if (parentRankStr == null) {
      return Optional.empty();
    }
    Rank child = parseRank(ctx.usage().getRank());
    Rank parent = parseRank(parentRankStr);
    if (child.notOtherOrUnranked() && parent.notOtherOrUnranked() && !parent.higherThan(child)) {
      return Optional.of(new Finding(key(), severity(),
          "rank '" + ctx.usage().getRank() + "' is not below its parent's rank '" + parentRankStr + "'",
          null));
    }
    return Optional.empty();
  }

  // Stored ranks are the parser Rank's lower-cased name(); reverse that, defaulting to UNRANKED
  // (uncomparable) for anything that doesn't map cleanly.
  private static Rank parseRank(String rank) {
    if (rank == null || rank.isBlank()) {
      return Rank.UNRANKED;
    }
    try {
      return Rank.valueOf(rank.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return Rank.UNRANKED;
    }
  }
}
