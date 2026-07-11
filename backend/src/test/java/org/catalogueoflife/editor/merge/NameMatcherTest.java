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

  @Test
  void equalAuthorsAreCompatible() {
    assertTrue(NameMatcher.authorCompatible("Mill.", "Mill."));
  }

  @Test
  void blankOnEitherSideIsCompatible() {
    assertTrue(NameMatcher.authorCompatible("", "Mill."));
    assertTrue(NameMatcher.authorCompatible(null, "Mill."));
    assertTrue(NameMatcher.authorCompatible("Mill.", null));
    assertTrue(NameMatcher.authorCompatible(null, null));
  }

  @Test
  void differentAuthorStringsAreNotCompatible() {
    // The brief's motivating case: "L." vs "(Linnaeus, 1758)" must NOT be treated as compatible --
    // this is what routes a same-canonical-key candidate to POSSIBLE_HOMONYM rather than MATCHED.
    assertFalse(NameMatcher.authorCompatible("L.", "(Linnaeus, 1758)"));
  }

  @Test
  void authorCompatibleIsCaseSpaceAndPunctuationInsensitive() {
    assertTrue(NameMatcher.authorCompatible("(Linnaeus, 1758)", "linnaeus 1758"));
  }
}
