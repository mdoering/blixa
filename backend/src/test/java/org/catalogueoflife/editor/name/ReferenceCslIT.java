package org.catalogueoflife.editor.name;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Task 1 of the reference-model-overhaul plan (docs/superpowers/plans/2026-07-12-reference-model-
// csl.md): author/editor are now structured CslName[] (JSONB, see V24__reference_csl.sql /
// CslNameListTypeHandler) instead of a "; "-joined free-text string, and the reference gains a
// short container title. Mirrors ReferenceApiIT's MockMvc + AbstractPostgresIT shape (real
// create/get through the HTTP endpoints), but exercises the structured-author JSON payload shape.
@AutoConfigureMockMvc
class ReferenceCslIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

  private void ensureUser(String username) {
    if (users.requireByUsernameOrNull(username) == null) users.createLocal(username, "pw", username);
  }

  private long createProject(String title) throws Exception {
    String body = mvc.perform(post("/api/projects").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"" + title + "\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  @Test
  @WithMockUser(username = "cslOwner")
  void structuredAuthorsAndShortContainerTitleRoundTrip() throws Exception {
    ensureUser("cslOwner");
    long pid = createProject("cslproj");

    String createPayload = """
        {
          "type": "article-journal",
          "author": [
            {"family": "Bánki", "given": "Olaf"},
            {"family": "Döring", "given": "Markus"}
          ],
          "title": "Catalogue of Life",
          "containerTitle": "Biodiversity Data Journal",
          "containerTitleShort": "Biodivers. Data J.",
          "issued": "2026"
        }
        """;
    String createBody = mvc.perform(post("/api/projects/" + pid + "/references").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createPayload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.author.length()").value(2))
        .andExpect(jsonPath("$.author[0].family").value("Bánki"))
        .andExpect(jsonPath("$.author[0].given").value("Olaf"))
        .andExpect(jsonPath("$.author[1].family").value("Döring"))
        .andExpect(jsonPath("$.author[1].given").value("Markus"))
        .andExpect(jsonPath("$.containerTitleShort").value("Biodivers. Data J."))
        .andReturn().getResponse().getContentAsString();
    JsonNode created = json.readTree(createBody);
    long refId = created.get("id").asLong();

    mvc.perform(get("/api/projects/" + pid + "/references/" + refId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.author.length()").value(2))
        .andExpect(jsonPath("$.author[0].family").value("Bánki"))
        .andExpect(jsonPath("$.author[0].given").value("Olaf"))
        .andExpect(jsonPath("$.author[1].family").value("Döring"))
        .andExpect(jsonPath("$.author[1].given").value("Markus"))
        .andExpect(jsonPath("$.type").value("article-journal"))
        .andExpect(jsonPath("$.title").value("Catalogue of Life"))
        .andExpect(jsonPath("$.containerTitle").value("Biodiversity Data Journal"))
        .andExpect(jsonPath("$.containerTitleShort").value("Biodivers. Data J."))
        .andExpect(jsonPath("$.issued").value("2026"));
  }
}
