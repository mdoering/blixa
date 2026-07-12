package org.catalogueoflife.editor.name;

import static org.assertj.core.api.Assertions.assertThat;

import org.catalogueoflife.editor.name.dto.CreateReferenceRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Pure unit tests for RefMapping.normalizeDoi and RefMapping.fromDatacite (no Spring/DB).
class RefMappingDoiTest {

  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final String BARE_DOI = "10.48580/dgy4k";

  @Test
  void normalizesBareDoi() {
    assertThat(RefMapping.normalizeDoi(BARE_DOI)).isEqualTo(BARE_DOI);
  }

  @Test
  void normalizesDoiPrefixedForm() {
    assertThat(RefMapping.normalizeDoi("doi:" + BARE_DOI)).isEqualTo(BARE_DOI);
    // the "doi:" prefix strip is case-insensitive
    assertThat(RefMapping.normalizeDoi("DOI:" + BARE_DOI)).isEqualTo(BARE_DOI);
  }

  @Test
  void normalizesResolverUrlForm() {
    assertThat(RefMapping.normalizeDoi("https://doi.org/" + BARE_DOI)).isEqualTo(BARE_DOI);
    assertThat(RefMapping.normalizeDoi("http://doi.org/" + BARE_DOI)).isEqualTo(BARE_DOI);
    assertThat(RefMapping.normalizeDoi("https://dx.doi.org/" + BARE_DOI)).isEqualTo(BARE_DOI);
  }

  @Test
  void normalizeDoiTrimsSurroundingWhitespace() {
    assertThat(RefMapping.normalizeDoi("  " + BARE_DOI + "  ")).isEqualTo(BARE_DOI);
    assertThat(RefMapping.normalizeDoi("  doi:" + BARE_DOI + "  ")).isEqualTo(BARE_DOI);
  }

  // Representative DataCite `data.attributes` JSON:API node for the Catalogue of Life dataset DOI,
  // mirroring the shape returned by GET https://api.datacite.org/dois/10.48580/dgy8b.
  @Test
  void mapsDataciteAttributes() {
    String json =
        """
        {
          "doi": "10.48580/dgy8b",
          "titles": [{"title": "Catalogue of Life"}],
          "creators": [
            {"name": "Bánki, Olaf", "familyName": "Bánki", "givenName": "Olaf"},
            {"name": "Roskov, Yury", "familyName": "Roskov", "givenName": "Yury"}
          ],
          "contributors": [
            {"name": "Doe, Jane", "familyName": "Doe", "givenName": "Jane", "contributorType": "Editor"},
            {"name": "Other, Person", "familyName": "Other", "givenName": "Person", "contributorType": "Other"}
          ],
          "publicationYear": 2026,
          "publisher": "Catalogue of Life Foundation",
          "url": "https://www.catalogueoflife.org/data/metadata",
          "types": {"resourceTypeGeneral": "Dataset"}
        }
        """;
    JsonNode attrs = JSON.readTree(json);

    CreateReferenceRequest r = RefMapping.fromDatacite(attrs);

    assertThat(r.title()).isEqualTo("Catalogue of Life");
    assertThat(r.author()).isEqualTo("Bánki, Olaf; Roskov, Yury");
    assertThat(r.editor()).isEqualTo("Doe, Jane");
    assertThat(r.issued()).isEqualTo("2026");
    assertThat(r.publisher()).isEqualTo("Catalogue of Life Foundation");
    assertThat(r.doi()).isEqualTo("10.48580/dgy8b");
    assertThat(r.link()).isEqualTo("https://www.catalogueoflife.org/data/metadata");
    assertThat(r.type()).isEqualTo("dataset");
    assertThat(r.citation()).doesNotContain("{").doesNotContain("}");
    assertThat(r.citation()).contains("Bánki, Olaf").contains("2026").contains("Catalogue of Life");
  }

  @Test
  void mapsDataciteContainerForPageRange() {
    String json =
        """
        {
          "doi": "10.5438/4k3m-nyvg",
          "titles": [{"title": "A Journal Article"}],
          "creators": [{"name": "Smith, Jane"}],
          "publicationYear": 2021,
          "container": {"title": "Some Journal", "volume": "12", "issue": "3",
                          "firstPage": "45", "lastPage": "67"}
        }
        """;
    JsonNode attrs = JSON.readTree(json);

    CreateReferenceRequest r = RefMapping.fromDatacite(attrs);

    assertThat(r.containerTitle()).isEqualTo("Some Journal");
    assertThat(r.volume()).isEqualTo("12");
    assertThat(r.issue()).isEqualTo("3");
    assertThat(r.page()).isEqualTo("45-67");
  }

  @Test
  void mapsDataciteMissingFieldsToNull() {
    JsonNode attrs = JSON.readTree("{}");

    CreateReferenceRequest r = RefMapping.fromDatacite(attrs);

    assertThat(r.title()).isNull();
    assertThat(r.author()).isNull();
    assertThat(r.editor()).isNull();
    assertThat(r.issued()).isNull();
    assertThat(r.citation()).isNull();
  }
}
