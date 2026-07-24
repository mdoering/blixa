package org.catalogueoflife.editor.validation.rules;

import java.util.Locale;
import java.util.Optional;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.validation.Finding;
import org.catalogueoflife.editor.validation.RuleContext;
import org.catalogueoflife.editor.validation.Severity;
import org.catalogueoflife.editor.validation.ValidationRule;
import org.gbif.nameparser.api.Rank;
import org.springframework.stereotype.Component;

// A name at genus rank or above that nonetheless carries a specific epithet (CLB HIGHER_RANK_BINOMIAL)
// -- e.g. a "genus" whose parsed name is a binomial. The rank and the atomised name disagree.
@Component
public class BinomialAboveGenusRule implements ValidationRule {

  @Override
  public String key() {
    return "binomial_above_genus";
  }

  @Override
  public Severity severity() {
    return Severity.WARNING;
  }

  @Override
  public Optional<Finding> evaluate(RuleContext ctx) {
    NameUsage u = ctx.usage();
    if (NameTypes.isNonScientific(u.getNameType())) {
      return Optional.empty();
    }
    boolean hasEpithet = u.getSpecificEpithet() != null && !u.getSpecificEpithet().isBlank();
    Rank rank = parseRank(u.getRank());
    if (hasEpithet && rank.notOtherOrUnranked() && rank.higherThan(Rank.SPECIES)) {
      return Optional.of(new Finding(key(), severity(),
          "rank '" + u.getRank() + "' is genus or above but the name has a specific epithet '"
              + u.getSpecificEpithet() + "'",
          null));
    }
    return Optional.empty();
  }

  // Stored ranks are the parser Rank's lower-cased name(); anything that doesn't map is UNRANKED
  // (uncomparable), mirroring RankVsParentRule.
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
