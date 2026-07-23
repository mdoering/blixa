package org.catalogueoflife.editor.name;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.catalogueoflife.editor.name.dto.CreateReferenceRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Pure unit tests for RefMapping.fromCslJson (CSL-JSON -> CreateReferenceRequest, no Spring/DB).
// CSL-JSON is the citeproc format Zotero/CrossRef "Get citation" and many tools export; it differs
// from the Crossref work message in that title/container-title are plain strings, `type` is already
// a CSL type, and institutions use `literal`.
class RefMappingCslJsonTest {

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  @Test
  void mapsASingleCslJsonObject() {
    String json =
        """
        {
          "type": "article-journal",
          "title": "Species Plantarum",
          "title-short": "Sp. Pl.",
          "container-title": "Journal of Botany",
          "container-title-short": "J. Bot.",
          "author": [
            {"family": "Smith", "given": "Jane"},
            {"literal": "World Flora Online"}
          ],
          "editor": [{"family": "Doe", "given": "John"}],
          "issued": {"date-parts": [[1899, 5, 2]]},
          "volume": "12",
          "issue": "3",
          "page": "45-67",
          "publisher": "Botanical Press",
          "DOI": "10.1/abc",
          "ISBN": "978-3-16-148410-0",
          "ISSN": "1234-5678",
          "URL": "https://example.org/x",
          "accessed": {"date-parts": [[2026, 7, 1]]}
        }
        """;
    List<CreateReferenceRequest> out = RefMapping.fromCslJson(JSON.readTree(json));
    assertThat(out).hasSize(1);
    CreateReferenceRequest r = out.get(0);
    assertThat(r.type()).isEqualTo("article-journal");
    assertThat(r.title()).isEqualTo("Species Plantarum");
    assertThat(r.titleShort()).isEqualTo("Sp. Pl.");
    assertThat(r.containerTitle()).isEqualTo("Journal of Botany");
    assertThat(r.containerTitleShort()).isEqualTo("J. Bot.");
    assertThat(r.author()).hasSize(2);
    assertThat(r.author().get(0).getFamily()).isEqualTo("Smith");
    assertThat(r.author().get(0).getGiven()).isEqualTo("Jane");
    // an institutional author (CSL `literal`) becomes a literal CslName, not a guessed family
    assertThat(r.author().get(1).getLiteral()).isEqualTo("World Flora Online");
    assertThat(r.editor().get(0).getFamily()).isEqualTo("Doe");
    assertThat(r.issued()).isEqualTo("1899");
    assertThat(r.volume()).isEqualTo("12");
    assertThat(r.issue()).isEqualTo("3");
    assertThat(r.page()).isEqualTo("45-67");
    assertThat(r.publisher()).isEqualTo("Botanical Press");
    assertThat(r.doi()).isEqualTo("10.1/abc");
    assertThat(r.isbn()).isEqualTo("978-3-16-148410-0");
    assertThat(r.issn()).isEqualTo("1234-5678");
    assertThat(r.link()).isEqualTo("https://example.org/x");
    assertThat(r.accessed()).isEqualTo("2026-07-01");
    assertThat(r.citation()).contains("Smith").contains("1899").contains("Species Plantarum");
  }

  @Test
  void mapsAnArrayOfObjects() {
    String json =
        """
        [
          {"type": "book", "title": "First", "author": [{"family": "A", "given": "B"}],
           "issued": {"date-parts": [[2001]]}},
          {"type": "article-journal", "title": "Second", "issued": {"date-parts": [[2002]]}}
        ]
        """;
    List<CreateReferenceRequest> out = RefMapping.fromCslJson(JSON.readTree(json));
    assertThat(out).hasSize(2);
    assertThat(out.get(0).title()).isEqualTo("First");
    assertThat(out.get(0).type()).isEqualTo("book");
    assertThat(out.get(0).issued()).isEqualTo("2001");
    assertThat(out.get(1).title()).isEqualTo("Second");
  }

  @Test
  void fallsBackToRawIssuedWhenNoDateParts() {
    String json = """
        {"title": "T", "issued": {"raw": "circa 1850"}}
        """;
    List<CreateReferenceRequest> out = RefMapping.fromCslJson(JSON.readTree(json));
    assertThat(out.get(0).issued()).isEqualTo("1850");
  }

  @Test
  void toleratesArrayTitleAndContainerTitle() {
    // Some producers wrongly emit CSL title/container-title as single-element arrays (Crossref-style).
    String json = """
        {"title": ["Arr Title"], "container-title": ["Arr Journal"]}
        """;
    List<CreateReferenceRequest> out = RefMapping.fromCslJson(JSON.readTree(json));
    assertThat(out.get(0).title()).isEqualTo("Arr Title");
    assertThat(out.get(0).containerTitle()).isEqualTo("Arr Journal");
  }
}
