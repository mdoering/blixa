package org.catalogueoflife.editor.name;

import static org.mockito.Mockito.when;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@WithMockUser(username = "refImportOwner")
class ReferenceImportIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

  // Stub the external Crossref HTTP so resolve-doi is deterministic and offline.
  @MockitoBean CrossrefClient crossref;

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
  void importsBibtexAndCreatesReferences() throws Exception {
    ensureUser("refImportOwner");
    long pid = createProject("refimport");

    String bibtex = "@article{k, author={Doe, Jane}, title={A Title}, journal={J}, year={2020}, doi={10.1/x}}";
    String body = mvc.perform(post("/api/projects/" + pid + "/references/import-bibtex").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("bibtex", bibtex))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$[0].title").value("A Title"))
        .andExpect(jsonPath("$[0].doi").value("10.1/x"))
        .andReturn().getResponse().getContentAsString();
    long refId = json.readTree(body).get(0).get("id").asLong();

    mvc.perform(get("/api/projects/" + pid + "/references/" + refId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("A Title"));
  }

  @Test
  void resolvesDoiIntoAPreview() throws Exception {
    ensureUser("refImportOwner");
    long pid = createProject("refresolve");

    when(crossref.fetchWork("10.9/z")).thenReturn(json.readTree(
        "{\"title\":[\"Crossref Title\"],\"DOI\":\"10.9/z\",\"type\":\"book\"}"));

    mvc.perform(post("/api/projects/" + pid + "/references/resolve-doi").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"doi\":\"10.9/z\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("Crossref Title"))
        .andExpect(jsonPath("$.doi").value("10.9/z"));

    // resolving does NOT persist anything
    mvc.perform(get("/api/projects/" + pid + "/references"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }
}
