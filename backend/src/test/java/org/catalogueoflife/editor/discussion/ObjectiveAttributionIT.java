package org.catalogueoflife.editor.discussion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// The work objective is an OPEN discussion (the task entity was retired). This exercises the
// objective attribution mechanism end-to-end: X-Objective-Id stamps change.discussion_id, an
// unheadered write is ungrouped, /changes?discussionId groups by objective, change rows carry the
// objective title, and a write under a non-OPEN or unknown objective is a 400 that rolls back.
// Ports the retired TaskApiIT grouping test onto discussions.
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "objOwner")
class ObjectiveAttributionIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

  private void ensureUser(String u) {
    if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u);
  }

  private long createProject(String title) throws Exception {
    String body = mvc.perform(post("/api/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"" + title + "\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  private long createObjective(long pid, String title) throws Exception {
    String body = mvc.perform(post("/api/projects/" + pid + "/discussions").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"" + title + "\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  @Test
  void attributesChangesToObjectiveViaHeaderAndFiltersChangelog() throws Exception {
    ensureUser("objOwner");
    long pid = createProject("objproj");
    long objId = createObjective(pid, "Revise Rosaceae");

    // 1) two edits under the objective (create + update a reference).
    String createBody = mvc.perform(post("/api/projects/" + pid + "/references").with(csrf())
            .header("X-Objective-Id", objId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"citation\":\"Miller 1768\",\"title\":\"Original\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    long refId = json.readTree(createBody).get("id").asLong();
    mvc.perform(put("/api/projects/" + pid + "/references/" + refId).with(csrf())
            .header("X-Objective-Id", objId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"citation\":\"Miller 1768\",\"title\":\"Revised\",\"version\":0}"))
        .andExpect(status().isOk());

    // 2) one edit WITHOUT the header -- ungrouped.
    mvc.perform(post("/api/projects/" + pid + "/references").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"citation\":\"Unlinked ref\",\"title\":\"Unlinked\"}"))
        .andExpect(status().isCreated());

    // 3) GET /changes?discussionId=D returns exactly the two, each carrying the objective title.
    String changesBody = mvc.perform(get("/api/projects/" + pid + "/changes")
            .param("discussionId", String.valueOf(objId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andReturn().getResponse().getContentAsString();
    for (JsonNode n : json.readTree(changesBody)) {
      assertThat(n.get("discussionId").asLong()).isEqualTo(objId);
      assertThat(n.get("discussionTitle").asString()).isEqualTo("Revise Rosaceae");
    }

    // 4) the full changelog has 3 rows, one of them ungrouped (discussionId null, no title).
    String allBody = mvc.perform(get("/api/projects/" + pid + "/changes"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andReturn().getResponse().getContentAsString();
    boolean sawUngrouped = false;
    for (JsonNode n : json.readTree(allBody)) {
      if (n.get("discussionId").isNull()) {
        sawUngrouped = true;
        assertThat(n.get("discussionTitle").isNull()).isTrue();
      }
    }
    assertThat(sawUngrouped).isTrue();

    // 5) a write under a RESOLVED (non-OPEN) objective -> 400, rolled back (still 3 changes total).
    mvc.perform(post("/api/projects/" + pid + "/discussions/" + objId + "/status").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\"}"))
        .andExpect(status().isOk());
    mvc.perform(post("/api/projects/" + pid + "/references").with(csrf())
            .header("X-Objective-Id", objId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"citation\":\"Should not persist\",\"title\":\"Nope\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/projects/" + pid + "/changes"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));

    // 6) X-Objective-Id = a nonexistent id -> 400, nothing persisted.
    mvc.perform(post("/api/projects/" + pid + "/references").with(csrf())
            .header("X-Objective-Id", "999999")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"citation\":\"Foreign objective\",\"title\":\"Nope\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/projects/" + pid + "/changes"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));
  }
}
