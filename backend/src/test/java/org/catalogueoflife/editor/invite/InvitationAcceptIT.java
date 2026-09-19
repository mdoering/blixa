package org.catalogueoflife.editor.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import org.catalogueoflife.editor.notify.EmailService;
import org.catalogueoflife.editor.project.ProjectMemberMapper;
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
class InvitationAcceptIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired AppUserMapper userMapper;
  @Autowired ProjectMemberMapper members;
  @Autowired InvitationMapper invitations;
  @Autowired ObjectMapper json;
  @MockitoBean EmailService email;

  private void ensureUser(String username) {
    if (users.requireByUsernameOrNull(username) == null) users.createLocal(username, "pw", username);
  }

  private int project(String owner, String title) throws Exception {
    String b = mvc.perform(post("/api/projects").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + title + "\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(b).get("id").asInt();
  }

  // Creates an invitation as `owner` and returns its token (the last path segment of acceptUrl).
  private String inviteToken(int pid, String owner, String mail, String role) throws Exception {
    String b = mvc.perform(post("/api/projects/" + pid + "/invitations").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + mail + "\",\"role\":\"" + role + "\",\"message\":\"Join us\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    String url = json.readTree(b).get("acceptUrl").asString();
    return url.substring(url.lastIndexOf('/') + 1);
  }

  @Test
  void pendingOrcidUserAcceptsAndBecomesActiveMember() throws Exception {
    ensureUser("accOwner");
    int pid = project("accOwner", "Accept IT");
    String token = inviteToken(pid, "accOwner", "ina@example.org", "editor");

    // unauthenticated preview
    mvc.perform(get("/api/public/invitations/" + token))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.projectTitle").value("Accept IT"))
       .andExpect(jsonPath("$.invitedBy").value("accOwner"))
       .andExpect(jsonPath("$.role").value("editor"))
       .andExpect(jsonPath("$.message").value("Join us"))
       .andExpect(jsonPath("$.status").value("VALID"));
    mvc.perform(get("/api/public/invitations/no-such-token")).andExpect(status().isNotFound());

    // a brand-new ORCID sign-up is PENDING with no email; accept must get past ActiveUserFilter
    String orcid = "0000-0002-9999-0201";
    users.upsertFromOrcid(orcid, "Ina Invitee", "Ina", "Invitee");
    mvc.perform(post("/api/invitations/" + token + "/accept").with(csrf()).with(user(orcid)))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.projectId").value(pid));

    AppUser ina = userMapper.findByOrcid(orcid);
    assertThat(ina.getState()).isEqualTo("ACTIVE");
    assertThat(ina.getEmail()).isEqualTo("ina@example.org");
    assertThat(members.findRole(pid, ina.getId())).isEqualTo("editor");

    // single use
    mvc.perform(get("/api/public/invitations/" + token)).andExpect(jsonPath("$.status").value("ACCEPTED"));
    mvc.perform(post("/api/invitations/" + token + "/accept").with(csrf()).with(user(orcid)))
       .andExpect(status().isGone());
  }

  @Test
  void existingMemberKeepsRoleAndExistingEmailIsKept() throws Exception {
    ensureUser("accOwner2");
    AppUser owner = users.requireByUsernameOrNull("accOwner2");
    owner.setEmail("owner2@example.org");
    userMapper.update(owner);
    int pid = project("accOwner2", "Accept IT 2");
    String token = inviteToken(pid, "accOwner2", "someone@example.org", "viewer");

    // the owner opens their own link: consumed, but never downgraded, and their email untouched
    mvc.perform(post("/api/invitations/" + token + "/accept").with(csrf()).with(user("accOwner2")))
       .andExpect(status().isOk());
    assertThat(members.findRole(pid, owner.getId())).isEqualTo("owner");
    assertThat(userMapper.findById(owner.getId()).getEmail()).isEqualTo("owner2@example.org");
  }

  @Test
  void expiredAndDisabledAreRejected() throws Exception {
    ensureUser("accOwner3");
    int pid = project("accOwner3", "Accept IT 3");

    String expired = inviteToken(pid, "accOwner3", "late@example.org", "editor");
    invitations.updateToken(invitations.findByToken(expired).getId(), expired, OffsetDateTime.now().minusDays(1));
    mvc.perform(get("/api/public/invitations/" + expired)).andExpect(jsonPath("$.status").value("EXPIRED"));
    ensureUser("accLate");
    mvc.perform(post("/api/invitations/" + expired + "/accept").with(csrf()).with(user("accLate")))
       .andExpect(status().isGone());

    String token = inviteToken(pid, "accOwner3", "gone@example.org", "editor");
    ensureUser("accDisabled");
    AppUser disabled = users.requireByUsernameOrNull("accDisabled");
    disabled.setState("DISABLED");
    userMapper.update(disabled);
    mvc.perform(post("/api/invitations/" + token + "/accept").with(csrf()).with(user("accDisabled")))
       .andExpect(status().isForbidden());
    assertThat(members.findRole(pid, disabled.getId())).isNull();
    // still usable by someone else -- the rejected attempt didn't consume it
    mvc.perform(get("/api/public/invitations/" + token)).andExpect(jsonPath("$.status").value("VALID"));
  }
}
