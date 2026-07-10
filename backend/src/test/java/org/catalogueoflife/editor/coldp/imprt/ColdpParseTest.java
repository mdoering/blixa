package org.catalogueoflife.editor.coldp.imprt;

import static org.assertj.core.api.Assertions.assertThat;

import life.catalogue.api.vocab.NomStatus;
import org.catalogueoflife.editor.name.Status;
import org.gbif.nameparser.api.NomCode;
import org.junit.jupiter.api.Test;

// Pure unit test (no Spring context, no DB) for ColdpParse's reverse-vocab helpers: each case is
// the inverse of a NameUsageColdpWriter transform (coldpStatus/lower/join/joinInts), so these
// assertions are effectively round-trip checks against that writer's vocab.
class ColdpParseTest {

  @Test
  void parseStatusInvertsColdpStatus() {
    assertThat(ColdpParse.parseStatus("accepted")).isEqualTo(Status.ACCEPTED);
    assertThat(ColdpParse.parseStatus("synonym")).isEqualTo(Status.SYNONYM);
    assertThat(ColdpParse.parseStatus("ambiguous synonym")).isEqualTo(Status.SYNONYM);
    assertThat(ColdpParse.parseStatus("misapplied")).isEqualTo(Status.MISAPPLIED);
    // "provisionally accepted" is what coldpStatus(UNASSESSED) writes -- must round-trip back to it.
    assertThat(ColdpParse.parseStatus("provisionally accepted")).isEqualTo(Status.UNASSESSED);
    assertThat(ColdpParse.parseStatus("unassessed")).isEqualTo(Status.UNASSESSED);
  }

  @Test
  void parseStatusIsCaseInsensitiveAndTrims() {
    assertThat(ColdpParse.parseStatus("SYNONYM")).isEqualTo(Status.SYNONYM);
    assertThat(ColdpParse.parseStatus("  Accepted  ")).isEqualTo(Status.ACCEPTED);
  }

  @Test
  void parseStatusNullOrBlankIsNull() {
    assertThat(ColdpParse.parseStatus(null)).isNull();
    assertThat(ColdpParse.parseStatus("")).isNull();
    assertThat(ColdpParse.parseStatus("   ")).isNull();
  }

  @Test
  void parseStatusUnknownFallsBackToUnassessed() {
    // Unknown/garbage ColDP status values should not be dropped as null (which downstream code
    // could confuse with "no status recorded") -- they land on the safest non-accepted status.
    assertThat(ColdpParse.parseStatus("weird")).isEqualTo(Status.UNASSESSED);
  }

  @Test
  void parseEnumInvertsLowerForASingleWordConstant() {
    // NomCode.ZOOLOGICAL -> lower() -> "zoological" (NameUsageColdpWriter.write's `code` field).
    assertThat(ColdpParse.parseEnum(NomCode.class, "zoological")).isEqualTo(NomCode.ZOOLOGICAL);
  }

  @Test
  void parseEnumInvertsLowerForARealMultiWordConstant() {
    // NomStatus.NOT_ESTABLISHED -> lower() -> "not established" (life.catalogue.api.vocab.NomStatus,
    // used by NameUsage.nomStatus / NameUsageColdpWriter's nameStatus column) -- a real multi-word
    // enum constant in this codebase's vocab, round-tripped through the underscore<->space transform.
    assertThat(ColdpParse.parseEnum(NomStatus.class, "not established")).isEqualTo(NomStatus.NOT_ESTABLISHED);
    // Upper-case-with-underscores input (as ColDP occasionally emits, or a lenient re-import of our
    // own export) round-trips the same way.
    assertThat(ColdpParse.parseEnum(NomStatus.class, "NOT_ESTABLISHED")).isEqualTo(NomStatus.NOT_ESTABLISHED);
  }

  @Test
  void parseEnumUnknownVocabIsNull() {
    assertThat(ColdpParse.parseEnum(NomCode.class, "bogus")).isNull();
  }

  @Test
  void parseEnumNullOrBlankIsNull() {
    assertThat(ColdpParse.parseEnum(NomCode.class, null)).isNull();
    assertThat(ColdpParse.parseEnum(NomCode.class, "")).isNull();
    assertThat(ColdpParse.parseEnum(NomCode.class, "  ")).isNull();
  }

  @Test
  void csvSplitsTrimsAndDropsEmpties() {
    assertThat(ColdpParse.csv("a, b ,,c")).containsExactly("a", "b", "c");
  }

  @Test
  void csvNullIsEmptyList() {
    assertThat(ColdpParse.csv(null)).isEmpty();
  }

  @Test
  void csvIntsParsesAndSkipsUnparseable() {
    assertThat(ColdpParse.csvInts("1,2,x,3")).containsExactly(1, 2, 3);
  }

  @Test
  void csvIntsNullOrEmptyIsEmptyList() {
    assertThat(ColdpParse.csvInts(null)).isEmpty();
    assertThat(ColdpParse.csvInts("")).isEmpty();
  }

  @Test
  void intOrNullParsesTrimsAndHandlesEdgeCases() {
    assertThat(ColdpParse.intOrNull("42")).isEqualTo(42);
    assertThat(ColdpParse.intOrNull(" 42 ")).isEqualTo(42);
    assertThat(ColdpParse.intOrNull("")).isNull();
    assertThat(ColdpParse.intOrNull(null)).isNull();
    assertThat(ColdpParse.intOrNull("not-a-number")).isNull();
  }
}
