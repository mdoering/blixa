package org.catalogueoflife.editor.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class LocalLoginIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService userService;

  @BeforeEach
  void seed() {
    if (userService.requireByUsernameOrNull("alice") == null) {
      userService.createLocal("alice", "s3cret", "Alice Example");
    }
  }

  @Test
  void pingIsPublicUnderRealSecurity() throws Exception {
    mvc.perform(get("/api/ping")).andExpect(status().isOk());
  }

  @Test
  void anonymousMeIsUnauthorized() throws Exception {
    mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void formLoginSucceedsWithSeededPassword() throws Exception {
    mvc.perform(formLogin("/api/auth/login").user("alice").password("s3cret"))
       .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(username = "alice")
  void meReturnsCurrentUser() throws Exception {
    mvc.perform(get("/api/me"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.username").value("alice"));
  }
}
