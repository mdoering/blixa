package org.catalogueoflife.editor.nomenclature;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import life.catalogue.api.vocab.NomRelType;
import org.junit.jupiter.api.Test;

class RelTypesTest {

  @Test
  void parsesRawColdpSpellings() {
    assertEquals(NomRelType.BASIONYM, RelTypes.parse("BASIONYM"));
    assertEquals(NomRelType.BASIONYM, RelTypes.parse("basionym"));
    assertEquals(NomRelType.SPELLING_CORRECTION, RelTypes.parse("spelling-correction"));
    assertEquals(NomRelType.SPELLING_CORRECTION, RelTypes.parse("Spelling_Correction"));
    assertEquals(NomRelType.SPELLING_CORRECTION, RelTypes.parse(" spelling correction "));
    assertTrue(RelTypes.isHomotypic("BASIONYM"));
    assertFalse(RelTypes.isHomotypic("foo"));
    assertEquals("spelling correction", RelTypes.label(NomRelType.SPELLING_CORRECTION));
  }

  @Test
  void unknownOrBlankIsNull() {
    assertNull(RelTypes.parse("foo"));
    assertNull(RelTypes.parse(""));
    assertNull(RelTypes.parse(null));
  }

  @Test
  void homotypicLabelsMatchTheEnum() {
    assertEquals(
        java.util.Set.of("basionym", "homotypic", "spelling correction", "based on",
            "replacement name", "superfluous"),
        new java.util.HashSet<>(RelTypes.HOMOTYPIC_LABELS));
    assertEquals(6, RelTypes.HOMOTYPIC_LABELS.size());
  }
}
