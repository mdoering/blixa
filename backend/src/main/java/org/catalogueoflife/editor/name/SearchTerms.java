package org.catalogueoflife.editor.name;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Turns a search box value into the prefix-match forms the mappers use alongside their fuzzy /
 * full-text filters, so a partly typed word ("Pant", "Johns") already finds something. Called from
 * MyBatis {@code <bind>} via OGNL static method access.
 */
public final class SearchTerms {

  private SearchTerms() {}

  /** An ILIKE pattern matching values that start with q (LIKE wildcards escaped); null for blank q. */
  public static String prefixLike(String q) {
    if (q == null || q.isBlank()) return null;
    return q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
  }

  /**
   * A to_tsquery expression requiring every word of q as a word prefix ("johns 2006" ->
   * "johns:* &amp; 2006:*"); null when q holds no letters or digits. Only letters and digits survive,
   * so the result never carries tsquery syntax from the user.
   */
  public static String prefixTsQuery(String q) {
    if (q == null) return null;
    String tsq = Arrays.stream(q.split("[^\\p{L}\\p{N}]+"))
        .filter(t -> !t.isEmpty())
        .map(t -> t + ":*")
        .collect(Collectors.joining(" & "));
    return tsq.isEmpty() ? null : tsq;
  }
}
