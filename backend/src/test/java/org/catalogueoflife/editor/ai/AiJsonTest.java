package org.catalogueoflife.editor.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class AiJsonTest {

  private final ObjectMapper mapper = JsonMapper.builder().build();

  @Test
  void parsesCleanJsonIntoAllCategories() {
    String raw = """
        {"synonyms":[{"scientificName":"Aus vetus","authorship":"Mill.","nomStatus":null,"referenceDoi":"10.1/x"}],
         "vernacularNames":[{"name":"bug","language":"eng"}],
         "distributions":[{"area":"Europe"}],
         "descriptions":["a herb"],
         "references":[{"doi":"10.1/x","citation":"Ref 1859"}],
         "etymology":"from Latin"}""";
    AiSuggestions s = AiJson.parse(mapper, raw);
    assertThat(s.synonyms()).singleElement()
        .satisfies(x -> assertThat(x.scientificName()).isEqualTo("Aus vetus"));
    assertThat(s.references().get(0).doi()).isEqualTo("10.1/x");
    assertThat(s.etymology()).isEqualTo("from Latin");
  }

  @Test
  void slicesToOutermostObjectIgnoringProseAndFences() {
    String raw = "Sure! Here you go:\n```json\n{\"synonyms\":[],\"references\":[]}\n```\nHope that helps.";
    AiSuggestions s = AiJson.parse(mapper, raw);
    assertThat(s.synonyms()).isEmpty();
    assertThat(s.references()).isEmpty();
  }

  @Test
  void throwsWhenThereIsNoJsonObject() {
    assertThatThrownBy(() -> AiJson.parse(mapper, "no json here"))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> AiJson.parse(mapper, null)).isInstanceOf(ResponseStatusException.class);
  }
}
