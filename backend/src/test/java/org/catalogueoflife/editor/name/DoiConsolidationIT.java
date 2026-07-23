package org.catalogueoflife.editor.name;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

// DOI consolidation endpoint: builds a Crossref bibliographic search from an existing reference's
// structured fields and returns candidate DOIs. CrossrefClient (the external HTTP) is mocked, same
// as ReferenceImportIT; the query-building + candidate mapping is what's exercised here.
@AutoConfigureMockMvc
@WithMockUser(username = "doiConsolidateOwner")
class DoiConsolidationIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;
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
  void findsCandidateDoisForAReference() throws Exception {
    ensureUser("doiConsolidateOwner");
    long pid = createProject("doiconsolidate");

    // A structured reference with no DOI.
    String createBody = mvc.perform(post("/api/projects/" + pid + "/references").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"title":"On a new species","containerTitle":"Journal of Botany","issued":"1899",
                 "author":[{"family":"Smith","given":"Jane"}]}
                """))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    long refId = json.readTree(createBody).get("id").asLong();

    // The bibliographic query is built from title + container + year; the author from the family.
    when(crossref.searchWorks(contains("On a new species"), eq("Smith"), anyInt()))
        .thenReturn(json.readTree("""
            [
              {"DOI":"10.1/abc","score":88.5,"title":["On a new species"],
               "author":[{"family":"Smith","given":"Jane"}],
               "container-title":["Journal of Botany"],"issued":{"date-parts":[[1899]]}},
              {"DOI":"10.2/def","score":31.0,"title":["Something else"],
               "issued":{"date-parts":[[1900]]}}
            ]
            """));

    mvc.perform(get("/api/projects/" + pid + "/references/" + refId + "/doi-candidates"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].doi").value("10.1/abc"))
        .andExpect(jsonPath("$[0].score").value(88.5))
        .andExpect(jsonPath("$[0].title").value("On a new species"))
        .andExpect(jsonPath("$[0].author").value("Smith, Jane"))
        .andExpect(jsonPath("$[0].containerTitle").value("Journal of Botany"))
        .andExpect(jsonPath("$[0].year").value("1899"))
        .andExpect(jsonPath("$[1].doi").value("10.2/def"));
  }

  @Test
  void unknownReferenceIs404() throws Exception {
    ensureUser("doiConsolidateOwner");
    long pid = createProject("doiconsolidate-404");
    mvc.perform(get("/api/projects/" + pid + "/references/999999/doi-candidates"))
        .andExpect(status().isNotFound());
  }
}
