package org.catalogueoflife.editor.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

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

  @Test
  @WithMockUser(username = "alice")
  // Spring Security Test's .with(csrf())/formLogin() (used elsewhere in this suite, e.g.
  // formLoginSucceedsWithSeededPassword above) permanently swaps the shared CsrfFilter
  // bean's tokenRepository field (via reflection) to a session-based test repository for
  // the remaining lifetime of the cached ApplicationContext. Force a pristine context here
  // so this test genuinely exercises OUR configured CookieCsrfTokenRepository/handler,
  // regardless of what ran before it.
  @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
  void csrfProtectsMutationsAndRealTokenFlowWorks() throws Exception {
    // (a) A mutating request without any CSRF token must be rejected, even for an
    // authenticated (mocked) user, proving CSRF protection is actually enforced.
    mvc.perform(post("/api/projects")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"slug\":\"csrf-demo\",\"title\":\"Csrf Demo\"}"))
       .andExpect(status().isForbidden());

    // (b) A real GET response must carry the XSRF-TOKEN cookie (written by
    // CsrfCookieFilter forcing the deferred token to load), which the SPA then
    // echoes back as a cookie + X-XSRF-TOKEN header on the follow-up mutation.
    MvcResult getResult = mvc.perform(get("/api/me")).andExpect(status().isOk()).andReturn();
    Cookie xsrfCookie = getResult.getResponse().getCookie("XSRF-TOKEN");
    org.junit.jupiter.api.Assertions.assertNotNull(xsrfCookie, "XSRF-TOKEN cookie must be set on a GET response");

    mvc.perform(post("/api/projects")
            .contentType(MediaType.APPLICATION_JSON)
            .cookie(xsrfCookie)
            .header("X-XSRF-TOKEN", xsrfCookie.getValue())
            .content("{\"slug\":\"csrf-demo\",\"title\":\"Csrf Demo\"}"))
       .andExpect(status().isCreated());
  }
}
