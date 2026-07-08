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
 * name-parser 4.2.0 integration described in design-spec Appendix A.
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
  void parseIntoUnparsableNameNeverThrows() {
    // A BOLD BIN identifier - not a scientific name at all - is unparsable.
    NameUsage u = new NameUsage();
    u.setScientificName("BOLD:AAA0001");
    u.setRank("unranked");

    service.parseInto(u, null);

    assertEquals("UNPARSABLE", u.getParseState());
    assertEquals(NameType.OTHER, u.getNameType());
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
  void formatNameFallsBackToScientificNamePlusAuthorshipWhenUnparsable() {
    NameUsage u = new NameUsage();
    u.setScientificName("BOLD:AAA0001");

    String fallback = service.formatName(u, null, false);

    assertEquals("BOLD:AAA0001", fallback);
  }
}
