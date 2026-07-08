package org.catalogueoflife.editor.project;

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
class MemberApiIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ProjectMemberMapper members;
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
            .content("{\"title\":\"Molluscs\"}"))
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

  @Test
  @WithMockUser(username = "bossTwo")
  void nonOwnerMemberCannotSetMembers() throws Exception {
    ensureUser("bossTwo");
    ensureUser("editorTwo");
    ensureUser("thirdUser");

    String body = mvc.perform(post("/api/projects").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Spiders\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    long pid = json.readTree(body).get("id").asLong();

    AppUser editorTwo = users.requireByUsernameOrNull("editorTwo");
    members.upsert(new ProjectMember((int) pid, editorTwo.getId(), Role.EDITOR.dbValue()));

    // editorTwo is a member but not an owner: setting members must be forbidden.
    mvc.perform(put("/api/projects/" + pid + "/members").with(csrf()).with(user("editorTwo"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"thirdUser\",\"role\":\"viewer\"}"))
       .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(username = "bossThree")
  void ownerCanRemoveMember() throws Exception {
    ensureUser("bossThree");
    ensureUser("helperThree");

    String body = mvc.perform(post("/api/projects").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Insects\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    long pid = json.readTree(body).get("id").asLong();

    AppUser helperThree = users.requireByUsernameOrNull("helperThree");
    members.upsert(new ProjectMember((int) pid, helperThree.getId(), Role.EDITOR.dbValue()));

    mvc.perform(delete("/api/projects/" + pid + "/members/" + helperThree.getId()).with(csrf()))
       .andExpect(status().is2xxSuccessful());

    mvc.perform(get("/api/projects/" + pid + "/members"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$[?(@.username=='helperThree')]").isEmpty());
  }

  @Test
  @WithMockUser(username = "bossFour")
  void cannotRemoveLastOwner() throws Exception {
    ensureUser("bossFour");

    String body = mvc.perform(post("/api/projects").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Birds Two\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    long pid = json.readTree(body).get("id").asLong();

    long ownerId = users.requireByUsernameOrNull("bossFour").getId();

    mvc.perform(delete("/api/projects/" + pid + "/members/" + ownerId).with(csrf()))
       .andExpect(status().isConflict());
  }

  @Test
  @WithMockUser(username = "bossSix")
  void cannotDemoteLastOwner() throws Exception {
    ensureUser("bossSix");

    String body = mvc.perform(post("/api/projects").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Birds Three\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    long pid = json.readTree(body).get("id").asLong();

    // bossSix is the sole owner; demoting themselves to editor would orphan the project.
    mvc.perform(put("/api/projects/" + pid + "/members").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"bossSix\",\"role\":\"editor\"}"))
       .andExpect(status().isConflict());
  }

  @Test
  @WithMockUser(username = "bossFive")
  void setMemberWithInvalidRoleReturnsBadRequest() throws Exception {
    ensureUser("bossFive");
    ensureUser("helperFive");

    String body = mvc.perform(post("/api/projects").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Reptiles Two\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    long pid = json.readTree(body).get("id").asLong();

    mvc.perform(put("/api/projects/" + pid + "/members").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"helperFive\",\"role\":\"admin\"}"))
       .andExpect(status().isBadRequest());
  }
}
