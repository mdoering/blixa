package org.catalogueoflife.editor.project;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
  @WithMockUser(username = "favClbOwner")
  void favoriteClbDatasetsRoundTrip() throws Exception {
    ensureUser("favClbOwner");

    String body = mvc.perform(post("/api/projects").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Fav\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.favoriteClbDatasets").value(nullValue()))
        .andReturn().getResponse().getContentAsString();
    long projectId = json.readTree(body).get("id").asLong();

    mvc.perform(put("/api/projects/" + projectId + "/metadata").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Fav\",\"favoriteClbDatasets\":"
                + "[{\"key\":\"3LXR\",\"title\":\"Catalogue of Life\"}]}"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.favoriteClbDatasets[0].key").value("3LXR"))
       .andExpect(jsonPath("$.favoriteClbDatasets[0].title").value("Catalogue of Life"));

    // omitted from a later save -> carried over unchanged (same contract as identifierScopes)
    mvc.perform(put("/api/projects/" + projectId + "/metadata").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Fav 2\"}"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.title").value("Fav 2"))
       .andExpect(jsonPath("$.favoriteClbDatasets[0].key").value("3LXR"));

    mvc.perform(get("/api/projects/" + projectId))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.favoriteClbDatasets[0].title").value("Catalogue of Life"));
  }

  @Test
  @WithMockUser(username = "idScopesOwner")
  void identifierScopesRoundTrip() throws Exception {
    ensureUser("idScopesOwner");

    String body = mvc.perform(post("/api/projects").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Fungi\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated())
        // default null on create -- no identifierScopes sent, none stored.
        .andExpect(jsonPath("$.identifierScopes").value(nullValue()))
        .andReturn().getResponse().getContentAsString();
    long projectId = json.readTree(body).get("id").asLong();

    // "col" carries a CLB dataset key (matchable); "ipni" has none (datasetKey omitted -> null,
    // not matchable) -- both the scope and the optional datasetKey must round-trip.
    mvc.perform(put("/api/projects/" + projectId + "/metadata").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Fungi\",\"identifierScopes\":"
                + "[{\"scope\":\"col\",\"datasetKey\":\"3LXR\"},{\"scope\":\"ipni\"}]}"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.identifierScopes[0].scope").value("col"))
       .andExpect(jsonPath("$.identifierScopes[0].datasetKey").value("3LXR"))
       .andExpect(jsonPath("$.identifierScopes[1].scope").value("ipni"))
       .andExpect(jsonPath("$.identifierScopes[1].datasetKey").value(nullValue()));

    // identifierScopes omitted from a later metadata save -> must not be nulled/reset, the
    // previously-saved scopes carry over unchanged (same contract as gbifOccurrenceLayer).
    mvc.perform(put("/api/projects/" + projectId + "/metadata").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Fungi Updated\"}"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.title").value("Fungi Updated"))
       .andExpect(jsonPath("$.identifierScopes[0].scope").value("col"))
       .andExpect(jsonPath("$.identifierScopes[0].datasetKey").value("3LXR"))
       .andExpect(jsonPath("$.identifierScopes[1].scope").value("ipni"))
       .andExpect(jsonPath("$.identifierScopes[1].datasetKey").value(nullValue()));

    mvc.perform(get("/api/projects/" + projectId))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.identifierScopes[0].scope").value("col"))
       .andExpect(jsonPath("$.identifierScopes[0].datasetKey").value("3LXR"))
       .andExpect(jsonPath("$.identifierScopes[1].scope").value("ipni"))
       .andExpect(jsonPath("$.identifierScopes[1].datasetKey").value(nullValue()));
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

  @Test
  @WithMockUser(username = "deleteOwner")
  void ownerCanDeleteProject() throws Exception {
    ensureUser("deleteOwner");

    String body = mvc.perform(post("/api/projects").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Trash me\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    long projectId = json.readTree(body).get("id").asLong();

    mvc.perform(delete("/api/projects/" + projectId).with(csrf()))
       .andExpect(status().isOk());

    // Gone: the owner can no longer fetch it (404), and it drops out of their project list -- the
    // membership row went with it via ON DELETE CASCADE.
    mvc.perform(get("/api/projects/" + projectId))
       .andExpect(status().isNotFound());
    mvc.perform(get("/api/projects"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  @WithMockUser(username = "deleteOwner2")
  void deleteForbiddenForEditor() throws Exception {
    ensureUser("deleteOwner2");
    ensureUser("deleteEditor");

    String body = mvc.perform(post("/api/projects").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Keep me\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    int projectId = json.readTree(body).get("id").asInt();

    AppUser editor = users.requireByUsernameOrNull("deleteEditor");
    members.upsert(new ProjectMember(projectId, editor.getId(), Role.EDITOR.dbValue()));

    // An editor may edit metadata but not delete the whole project -- that is owner-only.
    mvc.perform(delete("/api/projects/" + projectId).with(csrf()).with(user("deleteEditor")))
       .andExpect(status().isForbidden());

    // And it is still there for the owner afterwards.
    mvc.perform(get("/api/projects/" + projectId).with(user("deleteOwner2")))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.title").value("Keep me"));
  }
}
