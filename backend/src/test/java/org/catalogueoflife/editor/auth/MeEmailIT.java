package org.catalogueoflife.editor.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class MeEmailIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;

  @Test
  @WithMockUser(username = "emailUser")
  void setsAndValidatesEmail() throws Exception {
    if (users.requireByUsernameOrNull("emailUser") == null) users.createLocal("emailUser", "pw", "E");

    // malformed -> 400
    mvc.perform(put("/api/me/email").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"not-an-email\"}"))
       .andExpect(status().isBadRequest());

    // blank -> 400
    mvc.perform(put("/api/me/email").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"\"}"))
       .andExpect(status().isBadRequest());

    // valid -> 200 and echoed back on /api/me
    mvc.perform(put("/api/me/email").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"emailuser@example.org\"}"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.email").value("emailuser@example.org"));

    mvc.perform(get("/api/me"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.email").value("emailuser@example.org"));
  }
}
