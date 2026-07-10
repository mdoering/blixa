package org.catalogueoflife.editor.name;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
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

// GET /usages/{id}/col-match (ColMatchService/ClbMatchClient): matches a single usage against the
// published COL checklist. The external CLB call is entirely mocked (ClbMatchClient) -- never a
// real network call -- mirroring how ReferenceImportIT mocks CrossrefClient.
@AutoConfigureMockMvc
@WithMockUser(username = "colMatchOwner")
class ColMatchIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

  @MockitoBean ClbMatchClient clb;

  private void ensureUser(String u) {
    if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u);
  }

  private long createProject() throws Exception {
    String b = mvc.perform(post("/api/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"colmatch\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(b).get("id").asLong();
  }

  private long createUsage(long pid, String name) throws Exception {
    String b = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"" + name + "\",\"rank\":\"species\",\"status\":\"accepted\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(b).get("id").asLong();
  }

  @Test
  void matchReturnsBestPlusAlternatives() throws Exception {
    ensureUser("colMatchOwner");
    long pid = createProject();
    long uid = createUsage(pid, "Panthera leo");

    when(clb.match(anyString(), any(), any(), any(), anyList())).thenReturn(json.readTree("""
        {
          "type": "EXACT",
          "usage": {
            "id": "6W3C4",
            "name": "Panthera leo",
            "authorship": "Linnaeus, 1758",
            "rank": "species",
            "status": "accepted",
            "classification": [
              {"name": "Animalia", "rank": "kingdom"},
              {"name": "Chordata", "rank": "phylum"},
              {"name": "Mammalia", "rank": "class"},
              {"name": "Carnivora", "rank": "order"},
              {"name": "Felidae", "rank": "family"},
              {"name": "Panthera", "rank": "genus"}
            ]
          },
          "alternatives": [
            {
              "id": "6W3C5",
              "name": "Panthera leo persica",
              "authorship": "Meyer, 1826",
              "rank": "subspecies",
              "status": "accepted",
              "classification": [
                {"name": "Animalia", "rank": "kingdom"},
                {"name": "Panthera", "rank": "genus"}
              ]
            }
          ]
        }
        """));

    mvc.perform(get("/api/projects/" + pid + "/usages/" + uid + "/col-match"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].colId").value("6W3C4"))
        .andExpect(jsonPath("$[0].name").value("Panthera leo"))
        .andExpect(jsonPath("$[0].authorship").value("Linnaeus, 1758"))
        .andExpect(jsonPath("$[0].rank").value("species"))
        .andExpect(jsonPath("$[0].status").value("accepted"))
        .andExpect(jsonPath("$[0].matchType").value("EXACT"))
        .andExpect(jsonPath("$[0].classification")
            .value("Animalia > Chordata > Mammalia > Carnivora > Felidae > Panthera"))
        .andExpect(jsonPath("$[1].colId").value("6W3C5"))
        .andExpect(jsonPath("$[1].matchType").value("ALTERNATIVE"))
        .andExpect(jsonPath("$.length()").value(2));
  }

  @Test
  void noMatchReturnsEmptyList() throws Exception {
    ensureUser("colMatchOwner");
    long pid = createProject();
    long uid = createUsage(pid, "Nonexistantus bogusii");

    when(clb.match(anyString(), any(), any(), any(), anyList())).thenReturn(json.readTree("""
        {
          "type": "NONE",
          "usage": null
        }
        """));

    mvc.perform(get("/api/projects/" + pid + "/usages/" + uid + "/col-match"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }
}
