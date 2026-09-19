package org.catalogueoflife.editor.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.notify.EmailService;
import org.catalogueoflife.editor.project.ProjectMember;
import org.catalogueoflife.editor.project.ProjectMemberMapper;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
class InvitationApiIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired AppUserMapper userMapper;
  @Autowired ProjectMemberMapper members;
  @Autowired ObjectMapper json;
  @MockitoBean EmailService email;

  // Named testUser, not user: a same-named method here would shadow the statically-imported
  // SecurityMockMvcRequestPostProcessors.user(String) for every overload throughout this class
  // (JLS single-static-import shadowing), breaking every .with(user("...")) call below.
  private AppUser testUser(String username, String mail) {
    if (users.requireByUsernameOrNull(username) == null) users.createLocal(username, "pw", username);
    AppUser u = users.requireByUsernameOrNull(username);
    u.setEmail(mail);
    userMapper.update(u);
    return u;
  }

  private int project(String owner, String title) throws Exception {
    String b = mvc.perform(post("/api/projects").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + title + "\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(b).get("id").asInt();
  }

  private String invite(int pid, String owner, String body) throws Exception {
    return mvc.perform(post("/api/projects/" + pid + "/invitations").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andReturn().getResponse().getContentAsString();
  }

  @Test
  void ownerInvitesListsResendsAndRevokes() throws Exception {
    testUser("invApiOwner", "owner@example.org");
    int pid = project("invApiOwner", "Invitations IT");

    // create -> 201, emailed To invitee with the owner on CC + Reply-To
    String created = mvc.perform(post("/api/projects/" + pid + "/invitations").with(csrf())
            .with(user("invApiOwner")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\" New.Person@example.org \",\"role\":\"editor\",\"message\":\"Welcome!\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.email").value("New.Person@example.org"))
        .andExpect(jsonPath("$.role").value("editor"))
        .andExpect(jsonPath("$.message").value("Welcome!"))
        .andExpect(jsonPath("$.invitedBy").value("invApiOwner"))
        .andExpect(jsonPath("$.expired").value(false))
        .andReturn().getResponse().getContentAsString();
    int id = json.readTree(created).get("id").asInt();
    String firstUrl = json.readTree(created).get("acceptUrl").asString();
    assertThat(firstUrl).contains("/invite/");
    verify(email).send(eq("New.Person@example.org"), eq("owner@example.org"), eq("owner@example.org"),
        contains("Invitations IT"), contains(firstUrl));

    // a second live invite for the same address (any case) -> 409
    mvc.perform(post("/api/projects/" + pid + "/invitations").with(csrf()).with(user("invApiOwner"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"new.person@EXAMPLE.org\",\"role\":\"viewer\"}"))
       .andExpect(status().isConflict());

    // validation -> 400
    mvc.perform(post("/api/projects/" + pid + "/invitations").with(csrf()).with(user("invApiOwner"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"not-an-email\",\"role\":\"editor\"}"))
       .andExpect(status().isBadRequest());
    mvc.perform(post("/api/projects/" + pid + "/invitations").with(csrf()).with(user("invApiOwner"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"x@example.org\",\"role\":\"boss\"}"))
       .andExpect(status().isBadRequest());

    // list
    mvc.perform(get("/api/projects/" + pid + "/invitations").with(user("invApiOwner")))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.length()").value(1))
       .andExpect(jsonPath("$[0].id").value(id));

    // resend -> new link, emailed again
    String resent = mvc.perform(post("/api/projects/" + pid + "/invitations/" + id + "/resend")
            .with(csrf()).with(user("invApiOwner")))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    String secondUrl = json.readTree(resent).get("acceptUrl").asString();
    assertThat(secondUrl).isNotEqualTo(firstUrl);
    verify(email, times(2)).send(eq("New.Person@example.org"), anyString(), anyString(),
        anyString(), anyString());

    // revoke -> 204, gone; revoking again -> 404
    mvc.perform(delete("/api/projects/" + pid + "/invitations/" + id).with(csrf()).with(user("invApiOwner")))
       .andExpect(status().isNoContent());
    mvc.perform(get("/api/projects/" + pid + "/invitations").with(user("invApiOwner")))
       .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(delete("/api/projects/" + pid + "/invitations/" + id).with(csrf()).with(user("invApiOwner")))
       .andExpect(status().isNotFound());
  }

  @Test
  void onlyOwnersManageInvitations() throws Exception {
    testUser("invApiOwner2", null);
    int pid = project("invApiOwner2", "Invitations IT 2");
    AppUser editor = testUser("invApiEditor", null);
    members.upsert(new ProjectMember(pid, editor.getId(), Role.EDITOR.dbValue()));
    testUser("invApiStranger", null);

    mvc.perform(get("/api/projects/" + pid + "/invitations").with(user("invApiEditor")))
       .andExpect(status().isForbidden());
    assertThat(invite(pid, "invApiEditor", "{\"email\":\"a@example.org\",\"role\":\"editor\"}"))
        .contains("owner required");
    mvc.perform(get("/api/projects/" + pid + "/invitations").with(user("invApiStranger")))
       .andExpect(status().isNotFound());
  }
}
