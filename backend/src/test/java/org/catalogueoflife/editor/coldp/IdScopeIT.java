package org.catalogueoflife.editor.coldp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import life.catalogue.api.model.Identifier;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
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
class IdScopeIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  @Test
  @WithMockUser(username = "idScopeUser")
  void listsIdentifierScopePrefixes() throws Exception {
    String body = mvc.perform(get("/api/coldp/id-scopes"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasItem("col")))
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasItem("gbif")))
        .andReturn().getResponse().getContentAsString();

    JsonNode scopes = json.readTree(body);
    assertThat(scopes.isArray()).isTrue();
    assertThat(scopes.size()).isEqualTo(Identifier.Scope.values().length);
    for (JsonNode n : scopes) {
      assertThat(n.isString()).isTrue();
    }
  }

  @Test
  void requiresAuthentication() throws Exception {
    mvc.perform(get("/api/coldp/id-scopes"))
        .andExpect(status().isUnauthorized());
  }
}
