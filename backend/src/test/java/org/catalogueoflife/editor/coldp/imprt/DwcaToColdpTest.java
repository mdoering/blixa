package org.catalogueoflife.editor.coldp.imprt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// Unit coverage for the DwC taxonomicStatus -> ColDP status normalization (the file-reading path is
// covered end-to-end by DwcaImportIT).
class DwcaToColdpTest {

  @Test
  void normalizesAcceptedVariants() {
    assertThat(DwcaToColdp.normalizeStatus("accepted")).isEqualTo("accepted");
    assertThat(DwcaToColdp.normalizeStatus("Accepted")).isEqualTo("accepted");
    assertThat(DwcaToColdp.normalizeStatus("valid")).isEqualTo("accepted");
  }

  @Test
  void foldsAllSynonymFlavoursToSynonym() {
    assertThat(DwcaToColdp.normalizeStatus("synonym")).isEqualTo("synonym");
    assertThat(DwcaToColdp.normalizeStatus("homotypic synonym")).isEqualTo("synonym");
    assertThat(DwcaToColdp.normalizeStatus("heterotypic_synonym")).isEqualTo("synonym");
    assertThat(DwcaToColdp.normalizeStatus("proParteSynonym")).isEqualTo("synonym");
  }

  @Test
  void mapsMisappliedAndProvisional() {
    assertThat(DwcaToColdp.normalizeStatus("misapplied")).isEqualTo("misapplied");
    assertThat(DwcaToColdp.normalizeStatus("doubtful")).isEqualTo("provisionally accepted");
    assertThat(DwcaToColdp.normalizeStatus("provisionally accepted")).isEqualTo("provisionally accepted");
  }

  @Test
  void blankOrUnknownIsNull() {
    assertThat(DwcaToColdp.normalizeStatus(null)).isNull();
    assertThat(DwcaToColdp.normalizeStatus("")).isNull();
    assertThat(DwcaToColdp.normalizeStatus("   ")).isNull();
    assertThat(DwcaToColdp.normalizeStatus("nonsense")).isNull();
  }
}
