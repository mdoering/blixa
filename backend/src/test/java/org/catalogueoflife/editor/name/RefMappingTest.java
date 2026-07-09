package org.catalogueoflife.editor.name;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.catalogueoflife.editor.name.dto.CreateReferenceRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Pure unit tests for the external-format mappers (no Spring/DB).
class RefMappingTest {

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  @Test
  void mapsBibtexEntry() {
    String bibtex =
        """
        @article{key1,
          author = {Doe, Jane and Smith, John},
          title = {A great paper},
          journal = {Journal of Things},
          year = {2020},
          volume = {12},
          number = {3},
          pages = {45--67},
          doi = {10.1/xyz}
        }
        """;
    List<CreateReferenceRequest> refs = RefMapping.fromBibtex(bibtex);
    assertThat(refs).hasSize(1);
    CreateReferenceRequest r = refs.get(0);
    assertThat(r.author()).isEqualTo("Doe, Jane; Smith, John");
    assertThat(r.title()).isEqualTo("A great paper");
    assertThat(r.containerTitle()).isEqualTo("Journal of Things");
    assertThat(r.issued()).isEqualTo("2020");
    assertThat(r.volume()).isEqualTo("12");
    assertThat(r.issue()).isEqualTo("3");
    assertThat(r.doi()).isEqualTo("10.1/xyz");
    assertThat(r.type()).isEqualTo("article");
    assertThat(r.citation()).contains("A great paper");
  }

  @Test
  void mapsCrossrefMessage() {
    String json =
        """
        {
          "title": ["Systema Naturae"],
          "author": [{"family": "Linnaeus", "given": "Carl"}],
          "container-title": ["Nature"],
          "issued": {"date-parts": [[1758, 1, 1]]},
          "volume": "1",
          "issue": "2",
          "page": "1-824",
          "publisher": "Salvius",
          "DOI": "10.5/abc",
          "ISSN": ["1234-5678"],
          "URL": "https://doi.org/10.5/abc",
          "type": "book"
        }
        """;
    JsonNode message = JSON.readTree(json);
    CreateReferenceRequest r = RefMapping.fromCrossref(message);
    assertThat(r.title()).isEqualTo("Systema Naturae");
    assertThat(r.author()).isEqualTo("Linnaeus, Carl");
    assertThat(r.containerTitle()).isEqualTo("Nature");
    assertThat(r.issued()).isEqualTo("1758");
    assertThat(r.doi()).isEqualTo("10.5/abc");
    assertThat(r.issn()).isEqualTo("1234-5678");
    assertThat(r.link()).isEqualTo("https://doi.org/10.5/abc");
    assertThat(r.type()).isEqualTo("book");
    assertThat(r.citation()).contains("Linnaeus").contains("Systema Naturae");
  }
}
