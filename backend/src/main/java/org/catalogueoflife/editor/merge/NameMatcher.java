package org.catalogueoflife.editor.merge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.catalogueoflife.editor.merge.dto.Candidate;
import org.catalogueoflife.editor.merge.dto.Category;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.dto.ScoredId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// Matches every SOURCE-project name-usage against the TARGET project and assigns each one a
// Category (see dto/Category.java), producing the `names` half of a merge run's MergePlan
// (MergeService.computePlan, a later task, calls NameMatcher.match once per merge). Two-stage
// matching, cheapest/most-precise first:
//
//   1. Exact structural match: canonicalKey(u) is an author-stripped, rank-qualified key
//      reconstructed from the atomized name-part fields NameParserService.parseInto populates
//      (falling back to the raw scientificName for names that failed to parse). Target usages are
//      grouped by canonicalKey once per match() call; a source usage's candidates are whatever
//      target usages share its key. Author compatibility (authorCompatible) then decides the
//      category among those candidates -- see match()'s javadoc for the exact partition.
//   2. Trigram fuzzy fallback: only when NO target usage shares the source's canonicalKey at all
//      (an empty candidate set) -- NameUsageMapper.findFuzzyCandidate's pg_trgm `%`/similarity()
//      query against target.scientificName, rank-qualified (same rank as the source, like the
//      exact-key path) and gated by coldp.merge.name-similarity (default 0.7).
//
// Deliberately conservative: a canonical-key match with an incompatible/ambiguous author is never
// silently treated as MATCHED (see POSSIBLE_HOMONYM/POSSIBLE below) -- false merges are far more
// costly to a curator than a few extra records surfaced for manual review.
@Service
public class NameMatcher {

  private final NameUsageMapper usages;
  private final double threshold;

  // POSSIBLE_FUZZY is review-only -- it's never auto-merged (see Category / this class's javadoc
  // below) -- so a generous threshold is safe, and actually desirable: the goal is to help a
  // curator SPOT near-duplicates, not to auto-decide for them. 0.7 is verified against real
  // Postgres 17 pg_trgm output: single-character typos of a binomial score 0.65-0.80 similarity,
  // while congeneric-but-different-species noise (e.g. "Panthera leo" vs "Panthera onca") stays
  // <= 0.50 -- 0.7 catches the former while staying comfortably clear of the latter.
  public NameMatcher(NameUsageMapper usages,
      @Value("${coldp.merge.name-similarity:0.7}") double threshold) {
    this.usages = usages;
    this.threshold = threshold;
  }

  /**
   * Matches every usage of {@code sourceProjectId} against {@code targetProjectId}, one {@link
   * Candidate} per source usage (so the returned list's size always equals the source project's
   * usage count). Category assignment, per source usage:
   *
   * <ul>
   *   <li>{@link Category#MATCHED} -- exactly one target usage shares the source's {@link
   *       #canonicalKey} AND is {@link #authorCompatible} with it. {@code targetId} is that
   *       usage's id, {@code score} is {@code 1.0}.
   *   <li>{@link Category#POSSIBLE} -- two or more same-key target usages are author-compatible
   *       (ambiguous -- ANY could be right). {@code targetId} is the first (lowest-id) of them, a
   *       suggestion only; {@code score} is {@code null}.
   *   <li>{@link Category#POSSIBLE_HOMONYM} -- one or more same-key target usages exist but NONE
   *       is author-compatible (e.g. a genuine homonym, or an author string that just doesn't
   *       match). {@code targetId} is the first same-key candidate, a suggestion only; {@code
   *       score} is {@code null}.
   *   <li>{@link Category#POSSIBLE_FUZZY} -- no target usage shares the source's canonicalKey at
   *       all, but {@link NameUsageMapper#findFuzzyCandidate} finds a target whose scientificName
   *       clears the configured trigram-similarity threshold. {@code targetId}/{@code score} come
   *       from that candidate.
   *   <li>{@link Category#NEW} -- neither an exact-key nor a fuzzy candidate exists. {@code
   *       targetId} and {@code score} are both {@code null}.
   * </ul>
   */
  public List<Candidate> match(int sourceProjectId, int targetProjectId) {
    List<NameUsage> targets = usages.findAllByProject(targetProjectId);
    Map<String, List<NameUsage>> byKey =
        targets.stream().collect(Collectors.groupingBy(NameMatcher::canonicalKey));

    List<Candidate> result = new ArrayList<>();
    for (NameUsage src : usages.findAllByProject(sourceProjectId)) {
      result.add(matchOne(src, byKey, targetProjectId));
    }
    return result;
  }

  private Candidate matchOne(NameUsage src, Map<String, List<NameUsage>> byKey, int targetProjectId) {
    String sourceId = String.valueOf(src.getId());
    List<NameUsage> candidates = byKey.getOrDefault(canonicalKey(src), List.of());
    if (!candidates.isEmpty()) {
      // Grouping preserves target insertion order (findAllByProject is ORDER BY id), so "first"
      // below is deterministically the lowest-id same-key target.
      List<NameUsage> compatible = candidates.stream()
          .filter(c -> authorCompatible(src.getAuthorship(), c.getAuthorship()))
          .collect(Collectors.toList());
      if (compatible.size() == 1) {
        return new Candidate(sourceId, Category.MATCHED, String.valueOf(compatible.get(0).getId()), 1.0);
      } else if (compatible.size() >= 2) {
        return new Candidate(sourceId, Category.POSSIBLE, String.valueOf(compatible.get(0).getId()), null);
      }
      return new Candidate(sourceId, Category.POSSIBLE_HOMONYM, String.valueOf(candidates.get(0).getId()), null);
    }
    ScoredId fuzzy = usages.findFuzzyCandidate(targetProjectId, src.getScientificName(), src.getRank(), threshold);
    if (fuzzy != null) {
      return new Candidate(sourceId, Category.POSSIBLE_FUZZY, String.valueOf(fuzzy.id()), fuzzy.score());
    }
    return new Candidate(sourceId, Category.NEW, null, null);
  }

  // author-stripped structural key: reconstruct from atomized fields (parseInto populated them),
  // fall back to the raw scientificName for unparsed names; rank-qualified so a genus != a species
  // of the same spelling, and notho-qualified so a nothotaxon (hybrid marker, e.g. "Genus xspecies")
  // never collapses onto the same key as the plain (non-hybrid) name of otherwise identical spelling
  // -- they are different names, not merge candidates.
  static String canonicalKey(NameUsage u) {
    String core;
    if (notBlank(u.getUninomial())) core = u.getUninomial();
    else if (notBlank(u.getGenus())) core = String.join(" ",
        s(u.getGenus()), s(u.getInfragenericEpithet()), s(u.getSpecificEpithet()),
        s(u.getInfraspecificEpithet()), s(u.getCultivarEpithet()));
    else core = s(u.getScientificName());
    return norm(core) + "|" + norm(u.getRank()) + "|" + (u.getNotho() == null ? "" : u.getNotho().name());
  }

  private static boolean notBlank(String x) { return x != null && !x.isBlank(); }
  private static String s(String x){ return x==null? "" : x.trim(); }
  private static String norm(String x){ return x==null? "" : x.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+"," "); }

  // heuristic: strip case + all non-alphanumerics; blank on either side = compatible. Conservative:
  // mismatched author strings (e.g. "L." vs "(Linnaeus, 1758)") are NOT compatible -> POSSIBLE_HOMONYM
  // (surfaced for review), never silently merged. Upgradeable to a GBIF AuthorComparator later.
  static boolean authorCompatible(String a, String b) {
    String na = normAuthor(a), nb = normAuthor(b);
    return na.isEmpty() || nb.isEmpty() || na.equals(nb);
  }
  private static String normAuthor(String a){ return a==null? "" : a.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]",""); }
}
