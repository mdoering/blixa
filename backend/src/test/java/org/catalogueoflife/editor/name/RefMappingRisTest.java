package org.catalogueoflife.editor.name;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import life.catalogue.api.model.CslName;
import org.catalogueoflife.editor.name.dto.CreateReferenceRequest;
import org.junit.jupiter.api.Test;

// Pure unit tests for RefMapping.fromRis (no Spring/DB) -- mirrors RefMappingTest's BibTeX coverage.
class RefMappingRisTest {

  // A realistic Zotero-style journal-article export: repeated AU lines, T2/journal fields, an
  // SP/EP page range, a DOI and an ISSN-shaped SN.
  private static final String JOURNAL_ARTICLE =
      """
      TY  - JOUR
      AU  - Doe, Jane
      AU  - Smith, John
      TI  - A great paper
      T2  - Journal of Things
      PY  - 2020/01/15/
      VL  - 12
      IS  - 3
      SP  - 45
      EP  - 67
      DO  - 10.1/xyz
      SN  - 1234-5678
      UR  - https://example.org/paper
      ID  - 12345
      ER  -\s
      """;

  @Test
  void mapsJournalArticle() {
    List<CreateReferenceRequest> refs = RefMapping.fromRis(JOURNAL_ARTICLE);
    assertThat(refs).hasSize(1);
    CreateReferenceRequest r = refs.get(0);
    assertThat(r.type()).isEqualTo("article-journal");
    assertThat(r.author()).hasSize(2);
    assertThat(r.author().get(0).getFamily()).isEqualTo("Doe");
    assertThat(r.author().get(0).getGiven()).isEqualTo("Jane");
    assertThat(r.author().get(1).getFamily()).isEqualTo("Smith");
    assertThat(r.author().get(1).getGiven()).isEqualTo("John");
    assertThat(r.title()).isEqualTo("A great paper");
    assertThat(r.containerTitle()).isEqualTo("Journal of Things");
    assertThat(r.issued()).isEqualTo("2020");
    assertThat(r.volume()).isEqualTo("12");
    assertThat(r.issue()).isEqualTo("3");
    assertThat(r.page()).isEqualTo("45-67");
    assertThat(r.doi()).isEqualTo("10.1/xyz");
    assertThat(r.issn()).isEqualTo("1234-5678");
    assertThat(r.isbn()).isNull();
    assertThat(r.link()).isEqualTo("https://example.org/paper");
    assertThat(r.remarks()).isEqualTo("ris:12345");
    assertThat(r.citation()).contains("A great paper");
  }

  @Test
  void mapsBookRecordWithEditorPublisherAndIsbn() {
    String ris =
        """
        TY  - BOOK
        AU  - Linnaeus, Carl
        A2  - Editor, Ed
        TI  - Systema Naturae
        PY  - 1758
        PB  - Salvius
        SN  - 978-3-16-148410-0
        ER  -\s
        """;
    List<CreateReferenceRequest> refs = RefMapping.fromRis(ris);
    assertThat(refs).hasSize(1);
    CreateReferenceRequest r = refs.get(0);
    assertThat(r.type()).isEqualTo("book");
    assertThat(r.author()).hasSize(1);
    assertThat(r.author().get(0).getFamily()).isEqualTo("Linnaeus");
    assertThat(r.author().get(0).getGiven()).isEqualTo("Carl");
    assertThat(r.editor()).hasSize(1);
    assertThat(r.editor().get(0).getFamily()).isEqualTo("Editor");
    assertThat(r.editor().get(0).getGiven()).isEqualTo("Ed");
    assertThat(r.title()).isEqualTo("Systema Naturae");
    assertThat(r.issued()).isEqualTo("1758");
    assertThat(r.publisher()).isEqualTo("Salvius");
    assertThat(r.isbn()).isEqualTo("978-3-16-148410-0");
    assertThat(r.issn()).isNull();
  }

  @Test
  void parsesMultipleRecordsInOneFile() {
    String ris = JOURNAL_ARTICLE + "\n" + """
        TY  - BOOK
        TI  - Another Title
        PY  - 1999
        ER  -\s
        """;
    List<CreateReferenceRequest> refs = RefMapping.fromRis(ris);
    assertThat(refs).hasSize(2);
    assertThat(refs.get(0).title()).isEqualTo("A great paper");
    assertThat(refs.get(1).title()).isEqualTo("Another Title");
    assertThat(refs.get(1).type()).isEqualTo("book");
    assertThat(refs.get(1).issued()).isEqualTo("1999");
  }

  @Test
  void joinsMultipleAuthorsAcrossAuAndA1() {
    String ris =
        """
        TY  - JOUR
        AU  - Doe, Jane
        A1  - Smith, John
        AU  - Brown, Alex
        TI  - Multi-author paper
        ER  -\s
        """;
    List<CreateReferenceRequest> refs = RefMapping.fromRis(ris);
    assertThat(refs).hasSize(1);
    List<CslName> authors = refs.get(0).author();
    assertThat(authors).hasSize(3);
    assertThat(authors.get(0).getFamily()).isEqualTo("Doe");
    assertThat(authors.get(0).getGiven()).isEqualTo("Jane");
    assertThat(authors.get(1).getFamily()).isEqualTo("Smith");
    assertThat(authors.get(1).getGiven()).isEqualTo("John");
    assertThat(authors.get(2).getFamily()).isEqualTo("Brown");
    assertThat(authors.get(2).getGiven()).isEqualTo("Alex");
  }

  @Test
  void toleratesUnknownTagsBlankLinesAndCrlf() {
    // CRLF line endings, a blank line inside the record, and an unrecognized "N1" (notes) tag that
    // has no mapping and must simply be ignored rather than blowing up the parse.
    String ris = "TY  - RPRT\r\n" +
        "N1  - some editorial note\r\n" +
        "\r\n" +
        "TI  - A Report\r\n" +
        "PY  - 2021\r\n" +
        "ER  - \r\n";
    List<CreateReferenceRequest> refs = RefMapping.fromRis(ris);
    assertThat(refs).hasSize(1);
    CreateReferenceRequest r = refs.get(0);
    assertThat(r.type()).isEqualTo("report");
    assertThat(r.title()).isEqualTo("A Report");
    assertThat(r.issued()).isEqualTo("2021");
  }

  @Test
  void unknownRisTypeCodeMapsToDocument() {
    String ris = """
        TY  - DATA
        TI  - A dataset
        ER  -\s
        """;
    assertThat(RefMapping.fromRis(ris).get(0).type()).isEqualTo("document");
  }

  @Test
  void blankInputIsRejected() {
    assertThatThrownBy(() -> RefMapping.fromRis(" "))
        .hasMessageContaining("no RIS provided");
  }

  @Test
  void noRecognizableRecordIsRejected() {
    assertThatThrownBy(() -> RefMapping.fromRis("just some text\nwith no tags at all"))
        .hasMessageContaining("no RIS entries found");
  }
}
