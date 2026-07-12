package org.catalogueoflife.editor.name;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

// Field reconciliation (facet + normalize), NOT a record merge: mirrors ReferenceApiIT's
// setup style but exercises the two new /facets/container-title endpoints.
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContainerTitleReconcileIT extends AbstractPostgresIT {

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

  private long createReference(long pid, String citation, String containerTitle) throws Exception {
    String body = "{\"citation\":\"" + citation + "\""
        + (containerTitle == null ? "" : ",\"containerTitle\":\"" + containerTitle + "\"") + "}";
    String createBody = mvc.perform(post("/api/projects/" + pid + "/references").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    JsonNode created = json.readTree(createBody);
    return created.get("id").asLong();
  }

  @Test
  @WithMockUser(username = "reconcileOwner")
  void facetAndMergeNormalizeContainerTitles() throws Exception {
    ensureUser("reconcileOwner");
    ensureUser("reconcileViewer");
    long pid = createProject("reconcileproj");

    AppUser viewer = users.requireByUsernameOrNull("reconcileViewer");
    members.upsert(new ProjectMember((int) pid, viewer.getId(), Role.VIEWER.dbValue()));

    createReference(pid, "Smith 1900, Notes I", "J. Bot.");
    createReference(pid, "Smith 1901, Notes II", "J. Bot.");
    createReference(pid, "Jones 1902, Notes III", "Journal of Botany");
    createReference(pid, "Doe 1903, Notes IV", null);

    // facet: two non-null values, counted and ordered by count desc
    mvc.perform(get("/api/projects/" + pid + "/references/facets/container-title"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].value").value("J. Bot."))
        .andExpect(jsonPath("$[0].count").value(2))
        .andExpect(jsonPath("$[1].value").value("Journal of Botany"))
        .andExpect(jsonPath("$[1].count").value(1));

    // a viewer may read the facet but not merge
    mvc.perform(get("/api/projects/" + pid + "/references/facets/container-title").with(user("reconcileViewer")))
        .andExpect(status().isOk());
    mvc.perform(post("/api/projects/" + pid + "/references/facets/container-title/merge")
            .with(csrf()).with(user("reconcileViewer"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(
                new org.catalogueoflife.editor.name.dto.ContainerTitleMergeRequest(
                    "Journal of Botany", java.util.List.of("J. Bot.", "Journal of Botany")))))
        .andExpect(status().isForbidden());

    // empty variants -> 400
    mvc.perform(post("/api/projects/" + pid + "/references/facets/container-title/merge")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(
                new org.catalogueoflife.editor.name.dto.ContainerTitleMergeRequest(
                    "Journal of Botany", java.util.List.of()))))
        .andExpect(status().isBadRequest());

    // merge: both variants normalized to the canonical value
    mvc.perform(post("/api/projects/" + pid + "/references/facets/container-title/merge")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(
                new org.catalogueoflife.editor.name.dto.ContainerTitleMergeRequest(
                    "Journal of Botany", java.util.List.of("J. Bot.", "Journal of Botany")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updated").value(3));

    // facet now returns a single normalized value with the combined count
    mvc.perform(get("/api/projects/" + pid + "/references/facets/container-title"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].value").value("Journal of Botany"))
        .andExpect(jsonPath("$[0].count").value(3));
  }
}
