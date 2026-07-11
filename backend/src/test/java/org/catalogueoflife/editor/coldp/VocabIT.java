package org.catalogueoflife.editor.coldp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import life.catalogue.api.vocab.NomStatus;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.gbif.nameparser.api.Rank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class VocabIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  @Test
  @WithMockUser(username = "vocabUser")
  void listsEnumVocabularies() throws Exception {
    String body = mvc.perform(get("/api/coldp/vocab"))
        .andExpect(status().isOk())
        // ranks are lower-cased (round-trip with NameUsage.rank's stored form)
        .andExpect(jsonPath("$.ranks", org.hamcrest.Matchers.hasItem("species")))
        .andExpect(jsonPath("$.ranks", org.hamcrest.Matchers.hasItem("genus")))
        // nomStatus entries carry the enum-name value plus both code-specific labels
        .andExpect(jsonPath("$.nomStatus").isArray())
        .andExpect(jsonPath("$.nomStatus[0].value").exists())
        .andExpect(jsonPath("$.nomStatus[0].botanical").exists())
        .andExpect(jsonPath("$.nomStatus[0].zoological").exists())
        .andExpect(jsonPath("$.gender").isArray())
        .andExpect(jsonPath("$.environment").isArray())
        .andReturn().getResponse().getContentAsString();

    JsonNode root = json.readTree(body);
    assertThat(root.get("ranks").size()).isEqualTo(Rank.values().length);
    assertThat(root.get("nomStatus").size()).isEqualTo(NomStatus.values().length);
    // rank values are lower-case; nomStatus values are the upper-case enum name
    assertThat(root.get("ranks").get(0).asString()).isEqualTo(root.get("ranks").get(0).asString().toLowerCase());
    assertThat(root.get("nomStatus")).allMatch(n -> n.get("value").asString().equals(n.get("value").asString().toUpperCase()));
    // botanical vs zoological labels genuinely differ (e.g. ESTABLISHED: "nomen validum" vs "available")
    JsonNode established = null;
    for (JsonNode n : root.get("nomStatus")) {
      if (n.get("value").asString().equals("ESTABLISHED")) established = n;
    }
    assertThat(established).isNotNull();
    assertThat(established.get("botanical").asString()).isEqualTo(NomStatus.ESTABLISHED.getBotanicalLabel());
    assertThat(established.get("zoological").asString()).isEqualTo(NomStatus.ESTABLISHED.getZoologicalLabel());
  }

  @Test
  void requiresAuthentication() throws Exception {
    mvc.perform(get("/api/coldp/vocab"))
        .andExpect(status().isUnauthorized());
  }
}
