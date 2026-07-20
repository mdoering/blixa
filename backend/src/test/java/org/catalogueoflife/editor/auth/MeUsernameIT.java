package org.catalogueoflife.editor.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
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
class MeUsernameIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;

  @Test
  @WithMockUser(username = "unameUser")
  void setUsernameUniqueAndValidated() throws Exception {
    if (users.requireByUsernameOrNull("unameUser") == null) users.createLocal("unameUser", "pw", "U");
    if (users.requireByUsernameOrNull("takenName") == null) users.createLocal("takenName", "pw", "T");

    // invalid form -> 400 (checked first: it doesn't change the acting user's username)
    mvc.perform(put("/api/me/username").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"has spaces\"}"))
       .andExpect(status().isBadRequest());

    // taken by someone else -> 409
    mvc.perform(put("/api/me/username").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"takenName\"}"))
       .andExpect(status().isConflict());

    // valid + unique -> 200 (done last: this renames the acting user)
    mvc.perform(put("/api/me/username").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"olaf_banki\"}"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.username").value("olaf_banki"));
  }
}
