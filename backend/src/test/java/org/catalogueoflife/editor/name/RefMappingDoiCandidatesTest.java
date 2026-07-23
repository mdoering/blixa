package org.catalogueoflife.editor.name;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.catalogueoflife.editor.name.dto.DoiCandidate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Pure unit tests for RefMapping.doiCandidates (Crossref /works search `items` -> DoiCandidate list,
// for DOI consolidation). No Spring/DB.
class RefMappingDoiCandidatesTest {

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  @Test
  void mapsCrossrefSearchItems() {
    // The `message.items` array shape of GET /works?query.bibliographic=... (score + the same
    // per-item fields as a single work message).
    String json =
        """
        [
          {
            "DOI": "10.1/abc",
            "score": 82.4,
            "title": ["On a new species"],
            "author": [{"family": "Smith", "given": "Jane"}],
            "container-title": ["Journal of Botany"],
            "issued": {"date-parts": [[1899, 5]]}
          },
          {
            "DOI": "10.2/def",
            "score": 40.1,
            "title": ["A different work"],
            "author": [{"family": "Doe", "given": "John"}],
            "container-title": ["Other Journal"],
            "issued": {"date-parts": [[1901]]}
          }
        ]
        """;
    List<DoiCandidate> out = RefMapping.doiCandidates(JSON.readTree(json));
    assertThat(out).hasSize(2);
    DoiCandidate c = out.get(0);
    assertThat(c.doi()).isEqualTo("10.1/abc");
    assertThat(c.score()).isEqualTo(82.4);
    assertThat(c.title()).isEqualTo("On a new species");
    assertThat(c.author()).isEqualTo("Smith, Jane");
    assertThat(c.containerTitle()).isEqualTo("Journal of Botany");
    assertThat(c.year()).isEqualTo("1899");
    assertThat(out.get(1).doi()).isEqualTo("10.2/def");
  }

  @Test
  void skipsItemsWithoutADoiAndHandlesEmptyOrNull() {
    String json = """
        [
          {"title": ["No DOI here"], "score": 10},
          {"DOI": "10.3/ghi", "title": ["Has DOI"], "score": 5}
        ]
        """;
    List<DoiCandidate> out = RefMapping.doiCandidates(JSON.readTree(json));
    assertThat(out).hasSize(1);
    assertThat(out.get(0).doi()).isEqualTo("10.3/ghi");

    assertThat(RefMapping.doiCandidates(JSON.readTree("[]"))).isEmpty();
    assertThat(RefMapping.doiCandidates(null)).isEmpty();
  }
}
