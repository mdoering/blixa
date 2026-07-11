package org.catalogueoflife.editor.name;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

// Mirrors ReferenceImportIT's BibTeX coverage but for POST /references/import-ris.
@AutoConfigureMockMvc
@WithMockUser(username = "refImportRisOwner")
class ReferenceImportRisIT extends AbstractPostgresIT {

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
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  @Test
  void importsRisAndCreatesReferences() throws Exception {
    ensureUser("refImportRisOwner");
    long pid = createProject("refimportris");

    String ris = """
        TY  - JOUR
        AU  - Doe, Jane
        TI  - A Title
        T2  - J
        PY  - 2020
        DO  - 10.1/x
        ER  -\s

        TY  - BOOK
        AU  - Smith, John
        TI  - Another Title
        PY  - 1999
        PB  - Publisher
        ER  -\s
        """;

    String body = mvc.perform(post("/api/projects/" + pid + "/references/import-ris").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("ris", ris))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].title").value("A Title"))
        .andExpect(jsonPath("$[0].author").value("Doe, Jane"))
        .andExpect(jsonPath("$[0].type").value("article-journal"))
        .andExpect(jsonPath("$[0].issued").value("2020"))
        .andExpect(jsonPath("$[0].doi").value("10.1/x"))
        .andExpect(jsonPath("$[1].title").value("Another Title"))
        .andExpect(jsonPath("$[1].author").value("Smith, John"))
        .andExpect(jsonPath("$[1].type").value("book"))
        .andExpect(jsonPath("$[1].issued").value("1999"))
        .andReturn().getResponse().getContentAsString();
    long refId = json.readTree(body).get(0).get("id").asLong();

    mvc.perform(get("/api/projects/" + pid + "/references/" + refId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("A Title"));
  }
}
