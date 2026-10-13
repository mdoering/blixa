package org.catalogueoflife.editor.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.catalogueoflife.editor.name.NameUsage;
import org.gbif.nameparser.api.NameType;
import org.gbif.nameparser.api.NomCode;
import org.junit.jupiter.api.Test;

/**
 * Plain unit test (no Spring, no DB) for {@link NameParserService}, exercising the GBIF
 * name-parser 5.0.0 integration described in design-spec Appendix A.
 */
class NameParserServiceTest {

  private final NameParserService service = new NameParserService();

  @Test
  void parseIntoBotanicalCombinationAuthorship() {
    NameUsage u = new NameUsage();
    u.setScientificName("Abies alba");
    u.setAuthorship("Mill.");
    u.setRank("species");

    service.parseInto(u, NomCode.BOTANICAL);

    assertEquals("Abies", u.getGenus());
    assertEquals("alba", u.getSpecificEpithet());
    assertEquals("Mill.", u.getCombinationAuthorship());
    assertNull(u.getCombinationExAuthorship());
    assertNull(u.getCombinationAuthorshipYear());
    assertNull(u.getBasionymAuthorship());
    assertEquals("species", u.getRank());
    assertEquals(NameType.SCIENTIFIC, u.getNameType());
    assertEquals("COMPLETE", u.getParseState());
  }

  @Test
  void parseIntoZoologicalBasionymAuthorship() {
    // A bracketed author on a species-rank name is the ORIGINAL COMBINATION's (basionym) author:
    // Puma concolor was originally described in genus Felis by Linnaeus in 1771.
    NameUsage u = new NameUsage();
    u.setScientificName("Puma concolor");
    u.setAuthorship("(Linnaeus, 1771)");
    u.setRank("species");

    service.parseInto(u, NomCode.ZOOLOGICAL);

    assertEquals("Puma", u.getGenus());
    assertEquals("concolor", u.getSpecificEpithet());
    assertNull(u.getCombinationAuthorship());
    assertEquals("Linnaeus", u.getBasionymAuthorship());
    assertEquals("1771", u.getBasionymAuthorshipYear());
    assertEquals("COMPLETE", u.getParseState());
  }

  @Test
  void parseIntoSanctioningAuthor() {
    // Since name-parser 5.2 the sanctioning author sits on each Authorship, not the ParsedName.
    NameUsage u = new NameUsage();
    u.setScientificName("Boletus edulis");
    u.setAuthorship("Bull. : Fr.");
    u.setRank("species");

    service.parseInto(u, NomCode.BOTANICAL);

    assertEquals("Bull.", u.getCombinationAuthorship());
    assertEquals("Fr.", u.getSanctioningAuthor());
    assertEquals("COMPLETE", u.getParseState());
  }

  @Test
  void parseIntoAnonymousAuthorship() {
    // An anonymous work has no authors; the atomized column keeps it as the code specific "anon.".
    NameUsage u = new NameUsage();
    u.setScientificName("Rosa canina");
    u.setAuthorship("anon.");
    u.setRank("species");

    service.parseInto(u, NomCode.BOTANICAL);

    assertEquals("anon.", u.getCombinationAuthorship());
    assertNull(u.getBasionymAuthorship());
  }

  @Test
  void parseIntoBracketedYear() {
    // A year known only from evidence outside the work keeps its square brackets (ICZN Rec. 22A.2.3).
    NameUsage u = new NameUsage();
    u.setScientificName("Puma concolor");
    u.setAuthorship("Linnaeus, [1771]");
    u.setRank("species");

    service.parseInto(u, NomCode.ZOOLOGICAL);

    assertEquals("Linnaeus", u.getCombinationAuthorship());
    assertEquals("[1771]", u.getCombinationAuthorshipYear());
  }

  @Test
  void parseIntoUnparsableNameNeverThrows() {
    // A BOLD BIN identifier - not a scientific name at all - is unparsable. Under name-parser 5.0.0
    // it is recognised specifically as an IDENTIFIER (4.2.0 classified it as the catch-all OTHER).
    NameUsage u = new NameUsage();
    u.setScientificName("BOLD:AAA0001");
    u.setRank("unranked");

    service.parseInto(u, null);

    assertEquals("UNPARSABLE", u.getParseState());
    assertEquals(NameType.IDENTIFIER, u.getNameType());
  }

  @Test
  void parseIntoClearsStaleAtomizedFieldsWhenNameBecomesUnparsable() {
    // Simulates an UPDATE: the same NameUsage instance is first parsed successfully, populating
    // the atomized fields, then re-parsed after the scientificName was edited to something
    // unparsable. The stale genus/specificEpithet/authorship from the first parse must not survive.
    NameUsage u = new NameUsage();
    u.setScientificName("Abies alba");
    u.setAuthorship("Mill.");
    u.setRank("species");
    service.parseInto(u, NomCode.BOTANICAL);
    assertEquals("Abies", u.getGenus());
    assertEquals("alba", u.getSpecificEpithet());
    assertEquals("Mill.", u.getCombinationAuthorship());

    u.setScientificName("BOLD:AAA0001");
    u.setAuthorship(null);
    service.parseInto(u, NomCode.BOTANICAL);

    assertEquals("UNPARSABLE", u.getParseState());
    assertNull(u.getGenus());
    assertNull(u.getSpecificEpithet());
    assertNull(u.getCombinationAuthorship());
  }

  @Test
  void formatNameRendersCanonicalString() {
    NameUsage u = new NameUsage();
    u.setScientificName("Abies alba");
    u.setAuthorship("Mill.");
    u.setRank("species");

    String canonical = service.formatName(u, NomCode.BOTANICAL, false);

    assertTrue(canonical != null && !canonical.isBlank());
    assertTrue(canonical.contains("Abies alba"), "expected canonical name to contain 'Abies alba' but was: " + canonical);
    assertEquals("Abies alba Mill.", canonical);
  }

  @Test
  void formatNameHtmlItalicizesEpithets() {
    NameUsage u = new NameUsage();
    u.setScientificName("Abies alba");
    u.setAuthorship("Mill.");
    u.setRank("species");

    String html = service.formatName(u, NomCode.BOTANICAL, true);

    assertEquals("<i>Abies</i> <i>alba</i> Mill.", html);
  }

  @Test
  void formatNameRendersInformalName() {
    // A semi-structured Informal name has no atomized ParsedName, but still renders via
    // Informal.toParsedName() instead of falling back to the raw input.
    NameUsage u = new NameUsage();
    u.setScientificName("Abies  sp.   BOLD:AAA0001");
    u.setRank("species");

    String canonical = service.formatName(u, NomCode.BOTANICAL, false);

    assertEquals("Abies sp. BOLD:AAA0001", canonical);
  }

  @Test
  void formatNameFallsBackToScientificNamePlusAuthorshipWhenUnparsable() {
    NameUsage u = new NameUsage();
    u.setScientificName("BOLD:AAA0001");

    String fallback = service.formatName(u, null, false);

    assertEquals("BOLD:AAA0001", fallback);
  }

  @Test
  void splitAuthorshipTakesTheTrailingAuthorshipVerbatim() {
    assertEquals(new NameParserService.NameAndAuthorship("Panthera leo", "(Linnaeus, 1758)"),
        service.splitAuthorship("Panthera leo (Linnaeus, 1758)", null, NomCode.ZOOLOGICAL));
    assertEquals(new NameParserService.NameAndAuthorship("Abies alba", "Mill."),
        service.splitAuthorship("Abies alba Mill.", "species", NomCode.BOTANICAL));
    assertEquals(
        new NameParserService.NameAndAuthorship("Abies alba subsp. nebrodensis", "(Mattei) Mattei"),
        service.splitAuthorship("Abies alba subsp. nebrodensis (Mattei) Mattei", null, null));
    assertEquals(new NameParserService.NameAndAuthorship("Felidae", "Fischer de Waldheim, 1817"),
        service.splitAuthorship("Felidae Fischer de Waldheim, 1817", "family", NomCode.ZOOLOGICAL));
  }

  @Test
  void splitAuthorshipLeavesNamesWithoutAuthorshipWhole() {
    assertEquals(new NameParserService.NameAndAuthorship("Panthera leo", null),
        service.splitAuthorship("Panthera leo", null, null));
    assertEquals(new NameParserService.NameAndAuthorship("Felis sp. \"Bornean form\"", null),
        service.splitAuthorship("Felis sp. \"Bornean form\"", null, null));
  }
}
