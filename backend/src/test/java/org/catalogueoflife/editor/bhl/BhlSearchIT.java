package org.catalogueoflife.editor.bhl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "coldp.bhl.api-key=test-key")
class BhlSearchIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ProjectMemberMapper members;
  @Autowired ObjectMapper json;

  @MockitoBean BhlClient bhlClient; // never call BHL for real

  private void ensureUser(String username) {
    AppUser existing = users.requireByUsernameOrNull(username);
    if (existing == null) users.createLocal(username, "pw", username);
  }

  private long createProject(String title) throws Exception {
    String body = mvc.perform(post("/api/projects").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"" + title + "\",\"nomCode\":\"botanical\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  @Test
  @WithMockUser(username = "bhlOwner")
  void reportsAvailabilityAndSearchesPublications() throws Exception {
    ensureUser("bhlOwner");
    ensureUser("bhlStranger");
    long pid = createProject("bhlproj");

    mvc.perform(get("/api/projects/" + pid + "/bhl/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(true));

    when(bhlClient.publicationSearch(any())).thenReturn(List.of(
        new BhlItem(123, "Genera Plantarum", "Linnaeus", "1753",
            "https://www.biodiversitylibrary.org/item/123")));
    mvc.perform(get("/api/projects/" + pid + "/bhl/publication-search").param("q", "Genera Plantarum"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].itemId").value(123))
        .andExpect(jsonPath("$[0].title").value("Genera Plantarum"))
        .andExpect(jsonPath("$[0].url").value("https://www.biodiversitylibrary.org/item/123"));

    // a non-member cannot use the project's BHL tooling
    mvc.perform(get("/api/projects/" + pid + "/bhl/config").with(user("bhlStranger")))
        .andExpect(status().is4xxClientError());
  }

  @Test
  @WithMockUser(username = "bhlRefOwner")
  void linksAndClearsAReferenceBhlItemGatedToEditors() throws Exception {
    ensureUser("bhlRefOwner");
    ensureUser("bhlRefViewer");
    long pid = createProject("bhlrefproj");
    AppUser viewer = users.requireByUsernameOrNull("bhlRefViewer");
    members.upsert(new ProjectMember((int) pid, viewer.getId(), Role.VIEWER.dbValue()));

    String refBody = mvc.perform(post("/api/projects/" + pid + "/references").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"citation\":\"Linnaeus 1753 Species Plantarum\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    long refId = json.readTree(refBody).get("id").asLong();

    // link -> the updated reference echoes bhlItemId, and a GET shows it
    mvc.perform(put("/api/projects/" + pid + "/references/" + refId + "/bhl-item/123").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.bhlItemId").value(123));
    mvc.perform(get("/api/projects/" + pid + "/references/" + refId))
        .andExpect(jsonPath("$.bhlItemId").value(123));

    // clear
    mvc.perform(delete("/api/projects/" + pid + "/references/" + refId + "/bhl-item").with(csrf()))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/projects/" + pid + "/references/" + refId))
        .andExpect(jsonPath("$.bhlItemId").isEmpty());

    // a viewer cannot link
    mvc.perform(put("/api/projects/" + pid + "/references/" + refId + "/bhl-item/9")
            .with(csrf()).with(user("bhlRefViewer")))
        .andExpect(status().isForbidden());
  }
}
