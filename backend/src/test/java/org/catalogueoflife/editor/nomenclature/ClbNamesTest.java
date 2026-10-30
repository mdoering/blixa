package org.catalogueoflife.editor.nomenclature;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import life.catalogue.api.model.Name;
import life.catalogue.api.vocab.NomStatus;
import org.catalogueoflife.editor.name.NameUsage;
import org.gbif.nameparser.api.NomCode;
import org.gbif.nameparser.api.Rank;
import org.junit.jupiter.api.Test;

class ClbNamesTest {

  @Test
  void mapsAtomizedPartsRankCodeAndId() {
    NameUsage u = new NameUsage();
    u.setId(7);
    u.setRank("species");
    u.setGenus("Aus");
    u.setSpecificEpithet("bus");
    u.setNomStatus(NomStatus.ESTABLISHED);
    u.setPublishedInYear(1758);
    Name n = ClbNames.toName(u, NomCode.ZOOLOGICAL);
    assertEquals("7", n.getId());
    assertEquals(Rank.SPECIES, n.getRank());
    assertEquals("Aus", n.getGenus());
    assertEquals("bus", n.getSpecificEpithet());
    assertEquals(NomStatus.ESTABLISHED, n.getNomStatus());
    assertEquals(NomCode.ZOOLOGICAL, n.getCode());
    assertEquals(1758, n.getPublishedInYear());
  }

  @Test
  void splitsPipeJoinedAuthorsAndExAuthors() {
    NameUsage u = new NameUsage();
    u.setId(1);
    u.setCombinationAuthorship("Mill.|L.");
    u.setCombinationExAuthorship("Sm.");
    Name n = ClbNames.toName(u, null);
    assertEquals(List.of("Mill.", "L."), n.getCombinationAuthorship().getAuthors());
    assertEquals(List.of("Sm."), n.getCombinationAuthorship().getExAuthors());
  }

  @Test
  void stripsBracketsOfABracketedYear() {
    NameUsage u = new NameUsage();
    u.setId(1);
    u.setBasionymAuthorshipYear("[1789]");
    Name n = ClbNames.toName(u, null);
    assertEquals("1789", n.getBasionymAuthorship().getYear());
  }

  @Test
  void unparsedUsageMapsWithoutError() {
    NameUsage u = new NameUsage();
    u.setId(2);
    u.setScientificName("Foo bar baz");
    u.setAuthorship("Someone");
    Name n = ClbNames.toName(u, null);
    assertEquals(Rank.UNRANKED, n.getRank());
    assertTrue(n.getCombinationAuthorship().isEmpty());
    assertEquals("Someone", n.getAuthorship());
    assertEquals("Foo bar baz", n.getScientificName());
  }
}
