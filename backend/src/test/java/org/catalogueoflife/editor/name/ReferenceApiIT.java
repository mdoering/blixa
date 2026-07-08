package org.catalogueoflife.editor.name;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.project.ProjectMember;
import org.catalogueoflife.editor.project.ProjectMemberMapper;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
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

@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReferenceApiIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ProjectMemberMapper members;
  @Autowired ObjectMapper json;

  private void ensureUser(String username) {
    AppUser existing = users.requireByUsernameOrNull(username);
    if (existing == null) users.createLocal(username, "pw", username);
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
  @WithMockUser(username = "refOwner")
  void crudAndSearchAndAuthz() throws Exception {
    ensureUser("refOwner");
    ensureUser("refViewer");
    long pid = createProject("refproj");

    AppUser viewer = users.requireByUsernameOrNull("refViewer");
    members.upsert(new ProjectMember((int) pid, viewer.getId(), Role.VIEWER.dbValue()));

    // create
    String createBody = mvc.perform(post("/api/projects/" + pid + "/references").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"citation\":\"Miller 1768, Gardeners Dictionary\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.version").value(0))
        .andExpect(jsonPath("$.citation").value("Miller 1768, Gardeners Dictionary"))
        .andReturn().getResponse().getContentAsString();
    JsonNode created = json.readTree(createBody);
    long refId = created.get("id").asLong();

    // list contains it
    mvc.perform(get("/api/projects/" + pid + "/references"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$[0].id").value(refId));

    // trigram fuzzy search matches, unrelated term returns empty
    mvc.perform(get("/api/projects/" + pid + "/references").param("q", "Gardeners"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$[0].id").value(refId));
    mvc.perform(get("/api/projects/" + pid + "/references").param("q", "zzzzz"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.length()").value(0));

    // negative limit/offset are clamped rather than passed straight through to SQL LIMIT/OFFSET
    // (which Postgres would reject with a 500)
    mvc.perform(get("/api/projects/" + pid + "/references").param("limit", "-1"))
       .andExpect(status().isOk());
    mvc.perform(get("/api/projects/" + pid + "/references").param("offset", "-1"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$[0].id").value(refId));

    // update with the loaded version bumps version to 1
    mvc.perform(put("/api/projects/" + pid + "/references/" + refId).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"citation\":\"Miller 1768, Gardeners Dictionary, revised\",\"version\":0}"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.version").value(1))
       .andExpect(jsonPath("$.citation").value("Miller 1768, Gardeners Dictionary, revised"));

    // retrying with the now-stale version conflicts
    mvc.perform(put("/api/projects/" + pid + "/references/" + refId).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"citation\":\"Stale update\",\"version\":0}"))
       .andExpect(status().isConflict());

    // a viewer may read but not write
    mvc.perform(post("/api/projects/" + pid + "/references").with(csrf()).with(user("refViewer"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"citation\":\"Viewer attempt\"}"))
       .andExpect(status().isForbidden());
    mvc.perform(put("/api/projects/" + pid + "/references/" + refId).with(csrf()).with(user("refViewer"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"citation\":\"Viewer attempt\",\"version\":1}"))
       .andExpect(status().isForbidden());
    mvc.perform(delete("/api/projects/" + pid + "/references/" + refId).with(csrf()).with(user("refViewer")))
       .andExpect(status().isForbidden());
    mvc.perform(get("/api/projects/" + pid + "/references/" + refId).with(user("refViewer")))
       .andExpect(status().isOk());

    // delete then confirm gone
    mvc.perform(delete("/api/projects/" + pid + "/references/" + refId).with(csrf()))
       .andExpect(status().isNoContent());
    mvc.perform(get("/api/projects/" + pid + "/references/" + refId))
       .andExpect(status().isNotFound());
  }
}
