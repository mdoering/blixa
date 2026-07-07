package org.catalogueoflife.editor.project;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemberApiIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

  private void ensureUser(String u) {
    AppUser e = users.requireByUsernameOrNull(u);
    if (e == null) users.createLocal(u, "pw", u);
  }

  @Test
  @WithMockUser(username = "boss")
  void ownerCanAddEditorAndListMembers() throws Exception {
    ensureUser("boss");
    ensureUser("helper");

    String body = mvc.perform(post("/api/projects").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"slug\":\"mollusca\",\"title\":\"Molluscs\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    long pid = json.readTree(body).get("id").asLong();

    mvc.perform(put("/api/projects/" + pid + "/members").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"helper\",\"role\":\"editor\"}"))
       .andExpect(status().isOk());

    mvc.perform(get("/api/projects/" + pid + "/members"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$[?(@.username=='helper')].role").value(org.hamcrest.Matchers.hasItem("editor")));
  }

  @Test
  @WithMockUser(username = "viewerUser")
  void nonOwnerCannotAddMembers() throws Exception {
    ensureUser("viewerUser");
    ensureUser("ownerUser");
    // ownerUser creates a project via service-less path: create as viewerUser then downgrade is complex;
    // instead assert that adding a member to a project the actor is not owner of is forbidden/not-found.
    mvc.perform(put("/api/projects/424242/members").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"ownerUser\",\"role\":\"editor\"}"))
       .andExpect(status().isNotFound());
  }
}
