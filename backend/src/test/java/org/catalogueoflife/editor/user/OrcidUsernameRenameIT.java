package org.catalogueoflife.editor.user;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

// An ORCID login sets the security principal to the ORCID iD (OrcidUserService returns the user
// keyed on "sub"), while the account's username can be changed to a custom handle. The current-user
// resolution must therefore match on username OR orcid -- otherwise renaming the username locks an
// ORCID user out of every endpoint (findByUsername(orcid) returns null -> 401).
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrcidUsernameRenameIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;

  @Test
  void orcidUserStillResolvesAfterUsernameChange() throws Exception {
    // ORCID login: username defaults to the ORCID iD.
    AppUser u = users.upsertFromOrcid("0000-0001-7757-1889", "Markus Döring", "Markus", "Döring");
    // The user picks a custom username.
    users.updateUsername(u.getId(), "markus");

    // The login principal is still the ORCID iD, NOT the new username. /api/me must resolve them.
    mvc.perform(get("/api/me").with(user("0000-0001-7757-1889")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("markus"))
        .andExpect(jsonPath("$.orcid").value("0000-0001-7757-1889"));
  }

  @Test
  void updateUsernameRejectsAnOrcidShapedName() {
    AppUser u = users.upsertFromOrcid("0000-0002-1111-2222", "X Y", "X", "Y");
    // A custom username that looks like an ORCID iD would collide with the orcid resolution key.
    assertThatThrownBy(() -> users.updateUsername(u.getId(), "0000-0002-3333-4444"))
        .isInstanceOf(ResponseStatusException.class);
  }
}
