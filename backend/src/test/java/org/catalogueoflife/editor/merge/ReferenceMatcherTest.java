package org.catalogueoflife.editor.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Plain unit test (no Spring, no DB) for {@link ReferenceMatcher}'s static {@code normDoi}/{@code
 * normCitation} helpers -- the normalization building blocks {@link
 * ReferenceMatcher#match(int, int)} (exercised against a real DB in {@link ReferenceMatcherIT})
 * is built on.
 */
class ReferenceMatcherTest {

  // -- normDoi --------------------------------------------------------------------------------

  @Test
  void doiWithHttpsDoiOrgPrefixNormalizesEqualToBareDoi() {
    assertEquals(ReferenceMatcher.normDoi("10.1234/abcd"),
        ReferenceMatcher.normDoi("https://doi.org/10.1234/abcd"));
  }

  @Test
  void doiWithHttpDxDoiOrgPrefixNormalizesEqualToBareDoi() {
    assertEquals(ReferenceMatcher.normDoi("10.1234/abcd"),
        ReferenceMatcher.normDoi("http://dx.doi.org/10.1234/abcd"));
  }

  @Test
  void doiWithDoiColonPrefixNormalizesEqualToBareDoi() {
    assertEquals(ReferenceMatcher.normDoi("10.1234/abcd"),
        ReferenceMatcher.normDoi("doi:10.1234/abcd"));
  }

  @Test
  void doiNormalizationIsCaseAndWhitespaceInsensitive() {
    assertEquals(ReferenceMatcher.normDoi("10.1234/abcd"),
        ReferenceMatcher.normDoi("  HTTPS://DOI.ORG/10.1234/ABCD  "));
  }

  @Test
  void blankOrNullDoiNormalizesToNull() {
    assertNull(ReferenceMatcher.normDoi(null));
    assertNull(ReferenceMatcher.normDoi(""));
    assertNull(ReferenceMatcher.normDoi("   "));
  }

  // -- normCitation -----------------------------------------------------------------------------

  @Test
  void citationNormalizationFoldsWhitespaceCaseAndTrailingPunctuation() {
    String expected = ReferenceMatcher.normCitation("Doe, J. 2020. A title. Journal, 1, 2-3");
    assertEquals(expected,
        ReferenceMatcher.normCitation("  DOE, J.   2020.  A TITLE.  Journal, 1, 2-3.,; "));
  }

  @Test
  void citationNormalizationCollapsesInternalWhitespace() {
    assertEquals(ReferenceMatcher.normCitation("a b c"), ReferenceMatcher.normCitation("a   b\tc"));
  }

  @Test
  void citationNormalizationStripsTrailingPunctuationOnly() {
    // trailing punctuation is stripped, but punctuation elsewhere in the string is preserved.
    assertEquals("doe, j. 2020", ReferenceMatcher.normCitation("Doe, J. 2020..."));
  }

  @Test
  void blankOrNullCitationNormalizesToNull() {
    assertNull(ReferenceMatcher.normCitation(null));
    assertNull(ReferenceMatcher.normCitation(""));
    assertNull(ReferenceMatcher.normCitation("   "));
  }
}
