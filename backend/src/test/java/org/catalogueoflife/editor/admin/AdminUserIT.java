package org.catalogueoflife.editor.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminUserIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired AppUserMapper mapper;

  private void ensureUser(String username) {
    if (users.requireByUsernameOrNull(username) == null) users.createLocal(username, "pw", username);
  }

  @Test
  @WithMockUser(username = "adminUser")
  void pendingGatedAdminApprovesAndSelfLockoutGuards() throws Exception {
    ensureUser("adminUser");
    users.markAdmin("adminUser");

    // a locally-created user is ACTIVE and can use the protected API
    ensureUser("plainUser");
    mvc.perform(get("/api/projects").with(user("plainUser"))).andExpect(status().isOk());

    // a new ORCID self-signup is PENDING
    AppUser pending = users.upsertFromOrcid("0000-0003-1111-2222", "Pending Person", "P", "Person");
    assertEquals("PENDING", mapper.findByOrcid("0000-0003-1111-2222").getState());

    // pending user: 403 on a protected endpoint, but /api/me works and reports PENDING
    mvc.perform(get("/api/projects").with(user("0000-0003-1111-2222")))
       .andExpect(status().isForbidden());
    mvc.perform(get("/api/me").with(user("0000-0003-1111-2222")))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.state").value("PENDING"));

    // a non-admin can't reach the admin API
    mvc.perform(get("/api/admin/users").with(user("plainUser"))).andExpect(status().isForbidden());

    // the admin lists users and approves the pending one
    mvc.perform(get("/api/admin/users")).andExpect(status().isOk());
    mvc.perform(post("/api/admin/users/" + pending.getId() + "/state").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"ACTIVE\"}"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.state").value("ACTIVE"));

    // now the (formerly pending) user can use the API
    mvc.perform(get("/api/projects").with(user("0000-0003-1111-2222"))).andExpect(status().isOk());

    // self-lockout guards: the admin can't demote or disable themselves
    AppUser admin = mapper.findByUsername("adminUser");
    mvc.perform(post("/api/admin/users/" + admin.getId() + "/admin").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"admin\":false}"))
       .andExpect(status().isBadRequest());
    mvc.perform(post("/api/admin/users/" + admin.getId() + "/state").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"DISABLED\"}"))
       .andExpect(status().isBadRequest());
  }
}
