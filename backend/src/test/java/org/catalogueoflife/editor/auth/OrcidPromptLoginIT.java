package org.catalogueoflife.editor.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

// The ORCID authorization redirect carries prompt=login only when the SPA asks for it (after an
// explicit sign-out), so ORCID's still-active SSO session can't silently sign the user back in.
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrcidPromptLoginIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;

  @Test
  void promptLoginIsPassedToOrcidOnlyWhenRequested() throws Exception {
    String plain = mvc.perform(get("/oauth2/authorization/orcid"))
        .andExpect(status().is3xxRedirection())
        .andReturn().getResponse().getRedirectedUrl();
    assertThat(plain).startsWith("https://orcid.org/oauth/authorize").doesNotContain("prompt=");

    String reauth = mvc.perform(get("/oauth2/authorization/orcid").param("prompt", "login"))
        .andExpect(status().is3xxRedirection())
        .andReturn().getResponse().getRedirectedUrl();
    assertThat(reauth).startsWith("https://orcid.org/oauth/authorize").contains("prompt=login");

    // Any other prompt value is ignored, not forwarded.
    String other = mvc.perform(get("/oauth2/authorization/orcid").param("prompt", "none"))
        .andExpect(status().is3xxRedirection())
        .andReturn().getResponse().getRedirectedUrl();
    assertThat(other).doesNotContain("prompt=");
  }
}
