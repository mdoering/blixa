package org.catalogueoflife.editor.discussion;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class DiscussionExternalIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ProjectMemberMapper members;
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
  @WithMockUser(username = "extOwner")
  void externalSubmissionWithToken() throws Exception {
    ensureUser("extOwner");
    ensureUser("extViewer");
    long pid = createProject("extproj");
    AppUser viewer = users.requireByUsernameOrNull("extViewer");
    members.upsert(new ProjectMember((int) pid, viewer.getId(), Role.VIEWER.dbValue()));

    String tok = "/api/projects/" + pid + "/discussion-token";
    String pub = "/api/public/projects/" + pid + "/discussions";

    // a viewer cannot manage the token
    mvc.perform(post(tok).with(csrf()).with(user("extViewer"))).andExpect(status().isForbidden());

    // owner generates a token
    String tokBody = mvc.perform(post(tok).with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").isNotEmpty())
        .andReturn().getResponse().getContentAsString();
    String token = json.readTree(tokBody).get("token").asString();

    // external POST with no token -> 401
    mvc.perform(post(pub).with(anonymous()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"From COL\",\"body\":\"a user report\"}"))
       .andExpect(status().isUnauthorized());

    // external POST with a wrong token -> 401
    mvc.perform(post(pub).with(anonymous()).header("X-Api-Token", "nope")
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}"))
       .andExpect(status().isUnauthorized());

    // external POST with the token -> 201, arrives as REVIEW via API
    String created = mvc.perform(post(pub).with(anonymous()).header("X-Api-Token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"From COL\",\"body\":\"a user report\",\"authorOrcid\":\"0000-0002-1111-2222\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("REVIEW"))
        .andExpect(jsonPath("$.createdVia").value("API"))
        .andReturn().getResponse().getContentAsString();
    long did = json.readTree(created).get("id").asLong();

    // it shows internally under the REVIEW filter; an editor accepts it -> OPEN
    mvc.perform(get("/api/projects/" + pid + "/discussions").param("status", "REVIEW"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.total").value(1));
    mvc.perform(post("/api/projects/" + pid + "/discussions/" + did + "/status").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OPEN\"}"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.status").value("OPEN"));

    // revoking the token blocks further external submissions
    mvc.perform(delete(tok).with(csrf())).andExpect(status().isNoContent());
    mvc.perform(post(pub).with(anonymous()).header("X-Api-Token", token)
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"y\"}"))
       .andExpect(status().isUnauthorized());
  }
}
