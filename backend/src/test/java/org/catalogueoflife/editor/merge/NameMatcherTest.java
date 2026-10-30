package org.catalogueoflife.editor.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.catalogueoflife.editor.name.NameUsage;
import org.gbif.nameparser.api.NamePart;
import org.junit.jupiter.api.Test;

/**
 * Plain unit test (no Spring, no DB) for {@link NameMatcher}'s static {@code canonicalKey}/{@code
 * authorCompatible} helpers -- the structural-matching building blocks {@link
 * NameMatcher#match(int, int)} (exercised against a real DB in {@link NameMatcherIT}) is built on.
 */
class NameMatcherTest {

  // -- canonicalKey --------------------------------------------------------------------------

  @Test
  void twoSpeciesSameGenusEpithetRankProduceEqualKeys() {
    NameUsage a = new NameUsage();
    a.setGenus("Panthera");
    a.setSpecificEpithet("leo");
    a.setRank("species");
    a.setAuthorship("(Linnaeus, 1758)");

    NameUsage b = new NameUsage();
    b.setGenus("Panthera");
    b.setSpecificEpithet("leo");
    b.setRank("species");
    b.setAuthorship("Smith, 1900");

    // authorship differs, but canonicalKey is author-stripped -- same genus+epithet+rank.
    assertEquals(NameMatcher.canonicalKey(a), NameMatcher.canonicalKey(b));
  }

  @Test
  void genusVsSpeciesOfTheSameSpellingProduceDifferentKeys() {
    // Same spelled core ("Aloe"), differing only by rank -- the rank qualifier must keep these
    // apart so a genus never canonical-key-matches a species (or vice versa) of the same name.
    NameUsage genus = new NameUsage();
    genus.setUninomial("Aloe");
    genus.setRank("genus");

    NameUsage species = new NameUsage();
    species.setUninomial("Aloe");
    species.setRank("species");

    assertNotEquals(NameMatcher.canonicalKey(genus), NameMatcher.canonicalKey(species));
  }

  @Test
  void unparsedNameFallsBackToScientificName() {
    // No atomized fields populated at all (as NameParserService leaves them on UNPARSABLE) --
    // canonicalKey must fall back to the raw scientificName rather than producing an empty core.
    NameUsage a = new NameUsage();
    a.setScientificName("BOLD:AAA0001");
    a.setRank("unranked");

    NameUsage b = new NameUsage();
    b.setScientificName("BOLD:AAA0001");
    b.setRank("unranked");

    assertEquals(NameMatcher.canonicalKey(a), NameMatcher.canonicalKey(b));
    // Trailing "|" is the (empty, since notho is null here) notho segment -- see canonicalKey.
    assertEquals("bold:aaa0001|unranked|", NameMatcher.canonicalKey(a));
  }

  @Test
  void nothotaxonAndPlainNameOfSameSpellingProduceDifferentKeys() {
    // Same genus+epithet+rank, but one is a nothotaxon (hybrid marker, e.g. "Genus xspecies") and
    // the other is the plain (non-hybrid) name -- these are different names and must NOT collapse
    // onto the same canonicalKey (see the brief's motivating notho case).
    NameUsage plain = new NameUsage();
    plain.setGenus("Panthera");
    plain.setSpecificEpithet("leo");
    plain.setRank("species");

    NameUsage hybrid = new NameUsage();
    hybrid.setGenus("Panthera");
    hybrid.setSpecificEpithet("leo");
    hybrid.setRank("species");
    hybrid.setNotho(NamePart.SPECIFIC);

    assertNotEquals(NameMatcher.canonicalKey(plain), NameMatcher.canonicalKey(hybrid));
  }

  @Test
  void canonicalKeyIsWhitespaceAndCaseInsensitive() {
    NameUsage a = new NameUsage();
    a.setGenus("Panthera");
    a.setSpecificEpithet("leo");
    a.setRank("SPECIES");

    NameUsage b = new NameUsage();
    b.setGenus("  panthera ");
    b.setSpecificEpithet(" LEO");
    b.setRank(" species ");

    assertEquals(NameMatcher.canonicalKey(a), NameMatcher.canonicalKey(b));
  }

  // -- authorCompatible -----------------------------------------------------------------------

  private static NameUsage parsed(String combination, String basionym, String year) {
    NameUsage u = new NameUsage();
    u.setGenus("Panthera");
    u.setSpecificEpithet("leo");
    u.setRank("species");
    u.setCombinationAuthorship(combination);
    u.setBasionymAuthorship(basionym);
    u.setCombinationAuthorshipYear(year);
    u.setAuthorship(String.join(" ", java.util.stream.Stream.of(basionym, combination).filter(x -> x != null).toList()));
    return u;
  }

  private static NameUsage raw(String authorship) {
    NameUsage u = new NameUsage();
    u.setScientificName("Panthera leo");
    u.setAuthorship(authorship);
    return u;
  }

  @Test
  void equalAuthorsAreCompatible() {
    assertTrue(NameMatcher.authorCompatible(parsed("Mill.", null, null), parsed("Mill.", null, null)));
  }

  @Test
  void abbreviatedAndFullAuthorAreCompatible() {
    // CHANGED vs the old string heuristic ("L." vs "Linnaeus" was incompatible)
    assertTrue(NameMatcher.authorCompatible(parsed("L.", null, null), parsed("Linnaeus", null, null)));
  }

  @Test
  void differentAuthorsAreIncompatible() {
    assertFalse(NameMatcher.authorCompatible(parsed("Mill.", null, null), parsed("L.", null, null)));
  }

  @Test
  void blankRawAuthorshipIsCompatible() {
    NameUsage withAuthor = parsed("Mill.", null, null);
    assertTrue(NameMatcher.authorCompatible(withAuthor, parsed(null, null, null)));
    assertTrue(NameMatcher.authorCompatible(parsed(null, null, null), withAuthor));
    assertTrue(NameMatcher.authorCompatible(raw(""), raw("Mill.")));
    assertTrue(NameMatcher.authorCompatible(raw(null), raw("Mill.")));
    assertTrue(NameMatcher.authorCompatible(raw("Mill."), raw(null)));
    assertTrue(NameMatcher.authorCompatible(raw(null), raw(null)));
  }

  @Test
  void unparsedNamesFallBackToRawStrings() {
    assertFalse(NameMatcher.authorCompatible(raw("Someone"), raw("Other")));
    assertTrue(NameMatcher.authorCompatible(raw("Someone"), raw("someone")));
  }

  @Test
  void differentRawStringsAreNotCompatibleWithoutAtomizedAuthors() {
    // unchanged: "L." vs "(Linnaeus, 1758)" cannot be judged from raw strings alone
    assertFalse(NameMatcher.authorCompatible(raw("L."), raw("(Linnaeus, 1758)")));
  }

  @Test
  void rawFallbackIsCaseSpaceAndPunctuationInsensitive() {
    assertTrue(NameMatcher.authorCompatible(raw("(Linnaeus, 1758)"), raw("linnaeus 1758")));
  }

  @Test
  void basionymAuthorshipIsComparedWhenCombinationAuthorshipIsAbsent() {
    // no combination authorship on either side: the basionym authorship alone decides
    assertTrue(NameMatcher.authorCompatible(parsed(null, "L.", null), parsed(null, "Linnaeus", null)));
    assertFalse(NameMatcher.authorCompatible(parsed(null, "L.", null), parsed(null, "Smith", null)));
  }

  @Test
  void bracketShapeMismatchIsIncompatible() {
    // combination-only "Smith, 1900" vs basionym-only "(Linnaeus, 1758)": no shared part, so the
    // one part of each side is compared with the other's
    NameUsage a = parsed("Smith", null, "1900");
    NameUsage b = parsed(null, "Linnaeus", null);
    b.setBasionymAuthorshipYear("1758");
    assertFalse(NameMatcher.authorCompatible(a, b));
  }

  @Test
  void sharedPartsMustAllBeEqual() {
    // a recombination by the same author of two different basionyms is a different name
    assertFalse(NameMatcher.authorCompatible(parsed("Jones", "Smith", null), parsed("Jones", "Brown", null)));
    assertTrue(NameMatcher.authorCompatible(parsed("Mill.", "L.", null), parsed("Mill.", "Linnaeus", null)));
  }

  @Test
  void partOnOneSideOnlyIsNotCompared() {
    // "Mill." vs "(L.) Mill.": incomplete data, not a conflict
    assertTrue(NameMatcher.authorCompatible(parsed("Mill.", null, null), parsed("Mill.", "L.", null)));
    assertTrue(NameMatcher.authorCompatible(parsed("Mill.", "L.", null), parsed("Mill.", null, null)));
  }

  @Test
  void differentYearsAreIncompatibleEvenWithinTheComparatorsTolerance() {
    // AuthorComparator accepts up to 11 years apart; merging does not
    assertFalse(NameMatcher.authorCompatible(parsed("Smith", null, "1850"), parsed("Smith", null, "1860")));
    assertTrue(NameMatcher.authorCompatible(parsed("Smith", null, "1850"), parsed("Smith", null, "1850")));
    NameUsage a = parsed("Jones", "Smith", null);
    NameUsage b = parsed("Jones", "Smith", null);
    a.setBasionymAuthorshipYear("1850");
    b.setBasionymAuthorshipYear("1851");
    assertFalse(NameMatcher.authorCompatible(a, b));
  }

  @Test
  void bracketsOnOneSideOnlyCompareAcross() {
    // "Smith, 1900" vs "(Smith, 1900)": the same author and year, only the brackets differ
    NameUsage b = parsed(null, "Smith", null);
    b.setBasionymAuthorshipYear("1900");
    assertTrue(NameMatcher.authorCompatible(parsed("Smith", null, "1900"), b));
  }

  @Test
  void yearOnOneSideOnly() {
    NameUsage a = parsed("L.", null, null);
    NameUsage b = parsed("L.", null, "1758");
    // AuthorComparator returns EQUAL when only one side has a year, so partial info stays MATCHED
    assertTrue(NameMatcher.authorCompatible(a, b));
    assertTrue(NameMatcher.authorCompatible(b, a));
  }
}
