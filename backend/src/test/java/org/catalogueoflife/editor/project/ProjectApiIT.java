package org.catalogueoflife.editor.project;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectApiIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ProjectMemberMapper members;
  @Autowired ObjectMapper json;

  private void ensureUser(String username) {
    AppUser existing = users.requireByUsernameOrNull(username);
    if (existing == null) users.createLocal(username, "pw", username);
  }

  @Test
  @WithMockUser(username = "creator")
  void createListGetAndUpdate() throws Exception {
    ensureUser("creator");

    mvc.perform(post("/api/projects").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Birds\",\"nomCode\":\"zoological\"}"))
       .andExpect(status().isCreated())
       .andExpect(jsonPath("$.title").value("Birds"))
       .andExpect(jsonPath("$.nomCode").value("zoological"))
       .andExpect(jsonPath("$.role").value("owner"))
       .andExpect(jsonPath("$.gbifOccurrenceLayer").value(true));

    mvc.perform(get("/api/projects"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$[0].title").value("Birds"));
  }

  @Test
  @WithMockUser(username = "outsider")
  void getForeignProjectIsNotFound() throws Exception {
    ensureUser("outsider");
    mvc.perform(get("/api/projects/999999"))
       .andExpect(status().isNotFound())
       .andExpect(jsonPath("$.error").exists());
  }

  @Test
  @WithMockUser(username = "metaOwner")
  void ownerCanUpdateMetadata() throws Exception {
    ensureUser("metaOwner");

    String body = mvc.perform(post("/api/projects").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Mammals\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    long projectId = json.readTree(body).get("id").asLong();

    // Save the exact wire values the frontend Selects send (SPDX license id + lowercase nomCode);
    // they must round-trip unchanged (regression for "invalid license: CC0-1.0").
    mvc.perform(put("/api/projects/" + projectId + "/metadata").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Mammals Updated\",\"alias\":\"Mamm\","
                + "\"description\":\"Updated description\",\"nomCode\":\"zoological\","
                + "\"license\":\"CC0-1.0\"}"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.title").value("Mammals Updated"))
       .andExpect(jsonPath("$.alias").value("Mamm"))
       .andExpect(jsonPath("$.description").value("Updated description"))
       .andExpect(jsonPath("$.nomCode").value("zoological"))
       .andExpect(jsonPath("$.license").value("CC0-1.0"))
       // gbifOccurrenceLayer omitted from the request -> must not be nulled/reset, the DB
       // default (true) carries over unchanged.
       .andExpect(jsonPath("$.gbifOccurrenceLayer").value(true));

    // The other permitted license also round-trips as its SPDX id, and gbifOccurrenceLayer can
    // be explicitly turned off.
    mvc.perform(put("/api/projects/" + projectId + "/metadata").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Mammals Updated\",\"license\":\"CC-BY-4.0\",\"gbifOccurrenceLayer\":false}"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.license").value("CC-BY-4.0"))
       .andExpect(jsonPath("$.gbifOccurrenceLayer").value(false));

    mvc.perform(get("/api/projects/" + projectId))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.title").value("Mammals Updated"))
       .andExpect(jsonPath("$.license").value("CC-BY-4.0"))
       .andExpect(jsonPath("$.gbifOccurrenceLayer").value(false));
  }

  @Test
  @WithMockUser(username = "metaOwner3")
  void updateMetadataRejectsDisallowedLicense() throws Exception {
    ensureUser("metaOwner3");

    String body = mvc.perform(post("/api/projects").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Insects\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    long projectId = json.readTree(body).get("id").asLong();

    mvc.perform(put("/api/projects/" + projectId + "/metadata").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Insects\",\"license\":\"MIT\"}"))
       .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(username = "metaOwner2")
  void updateMetadataForbiddenForViewer() throws Exception {
    ensureUser("metaOwner2");
    ensureUser("metaViewer");

    String body = mvc.perform(post("/api/projects").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Reptiles\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    int projectId = json.readTree(body).get("id").asInt();

    AppUser viewer = users.requireByUsernameOrNull("metaViewer");
    members.upsert(new ProjectMember(projectId, viewer.getId(), Role.VIEWER.dbValue()));

    mvc.perform(put("/api/projects/" + projectId + "/metadata").with(csrf()).with(user("metaViewer"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Reptiles Updated\"}"))
       .andExpect(status().isForbidden());
  }
}
