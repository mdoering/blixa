package org.catalogueoflife.editor.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

// Same MemberApiIT scenarios, but with the ORCID client-id reset to the "unconfigured" sentinel
// (application-test.yml normally registers a real client-id -- see ConfigApiIT) so ProjectService
// runs in local-auth mode: there's no ORCID self-registration in that mode, so an owner adding a
// member who has never logged in must auto-provision a local account rather than 404.
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.security.oauth2.client.registration.orcid.client-id=unconfigured")
class MemberApiLocalAuthIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

  private void ensureUser(String u) {
    AppUser e = users.requireByUsernameOrNull(u);
    if (e == null) users.createLocal(u, "pw", u);
  }

  @Test
  @WithMockUser(username = "localBoss")
  void ownerAddingUnknownUserAutoProvisionsLocalAccount() throws Exception {
    ensureUser("localBoss");
    assertThat(users.requireByUsernameOrNull("brandNewLocalUser")).isNull();

    String body = mvc.perform(post("/api/projects").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Lichens\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    long pid = json.readTree(body).get("id").asLong();

    mvc.perform(put("/api/projects/" + pid + "/members").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"brandNewLocalUser\",\"role\":\"editor\"}"))
       .andExpect(status().isOk());

    AppUser created = users.requireByUsernameOrNull("brandNewLocalUser");
    assertThat(created).isNotNull();

    mvc.perform(get("/api/projects/" + pid + "/members"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$[?(@.username=='brandNewLocalUser')].role")
           .value(org.hamcrest.Matchers.hasItem("editor")));
  }
}
