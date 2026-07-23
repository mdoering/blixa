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

// Mirrors ReferenceImportRisIT but for POST /references/import-csl-json (CSL-JSON array + single
// object + malformed-input guard).
@AutoConfigureMockMvc
@WithMockUser(username = "refImportCslOwner")
class ReferenceImportCslJsonIT extends AbstractPostgresIT {

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
  void importsCslJsonArrayAndCreatesReferences() throws Exception {
    ensureUser("refImportCslOwner");
    long pid = createProject("refimportcsl");

    String csl = """
        [
          {
            "type": "article-journal",
            "title": "A Title",
            "container-title": "Journal of Botany",
            "container-title-short": "J. Bot.",
            "author": [{"family": "Doe", "given": "Jane"}, {"literal": "World Flora Online"}],
            "issued": {"date-parts": [[2020, 3, 2]]},
            "volume": "5",
            "page": "1-10",
            "DOI": "10.1/x"
          },
          {"type": "book", "title": "Another Title", "author": [{"family": "Smith", "given": "John"}],
           "issued": {"date-parts": [[1999]]}, "publisher": "Publisher"}
        ]
        """;

    String body = mvc.perform(post("/api/projects/" + pid + "/references/import-csl-json").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("cslJson", csl))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].title").value("A Title"))
        .andExpect(jsonPath("$[0].containerTitle").value("Journal of Botany"))
        .andExpect(jsonPath("$[0].containerTitleShort").value("J. Bot."))
        .andExpect(jsonPath("$[0].author[0].family").value("Doe"))
        .andExpect(jsonPath("$[0].author[0].given").value("Jane"))
        .andExpect(jsonPath("$[0].author[1].literal").value("World Flora Online"))
        .andExpect(jsonPath("$[0].type").value("article-journal"))
        .andExpect(jsonPath("$[0].issued").value("2020"))
        .andExpect(jsonPath("$[0].doi").value("10.1/x"))
        .andExpect(jsonPath("$[1].title").value("Another Title"))
        .andExpect(jsonPath("$[1].type").value("book"))
        .andExpect(jsonPath("$[1].issued").value("1999"))
        .andReturn().getResponse().getContentAsString();
    long refId = json.readTree(body).get(0).get("id").asLong();

    mvc.perform(get("/api/projects/" + pid + "/references/" + refId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("A Title"));
  }

  @Test
  void importsASingleCslJsonObject() throws Exception {
    ensureUser("refImportCslOwner");
    long pid = createProject("refimportcsl-single");

    String csl = """
        {"type": "article-journal", "title": "Solo", "issued": {"date-parts": [[2011]]}}
        """;
    mvc.perform(post("/api/projects/" + pid + "/references/import-csl-json").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("cslJson", csl))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].title").value("Solo"));
  }

  @Test
  void malformedJsonIsBadRequest() throws Exception {
    ensureUser("refImportCslOwner");
    long pid = createProject("refimportcsl-bad");

    mvc.perform(post("/api/projects/" + pid + "/references/import-csl-json").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("cslJson", "{not json"))))
        .andExpect(status().isBadRequest());

    // valid JSON but no mappable item (empty array) -> 400
    mvc.perform(post("/api/projects/" + pid + "/references/import-csl-json").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("cslJson", "[]"))))
        .andExpect(status().isBadRequest());
  }
}
