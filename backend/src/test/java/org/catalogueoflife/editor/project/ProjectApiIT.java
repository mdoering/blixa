package org.catalogueoflife.editor.project;

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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectApiIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;

  private void ensureUser(String username) {
    AppUser existing = users.requireByUsernameOrNull(username);
    if (existing == null) users.createLocal(username, "pw", username);
  }

  @Test
  @WithMockUser(username = "creator")
  void createListGetAndUpdate() throws Exception {
    ensureUser("creator");

    mvc.perform(post("/api/projects").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"slug\":\"aves\",\"title\":\"Birds\",\"nomCode\":\"zoological\"}"))
       .andExpect(status().isCreated())
       .andExpect(jsonPath("$.slug").value("aves"))
       .andExpect(jsonPath("$.role").value("owner"));

    mvc.perform(get("/api/projects"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$[0].slug").value("aves"));
  }

  @Test
  @WithMockUser(username = "outsider")
  void getForeignProjectIsNotFound() throws Exception {
    ensureUser("outsider");
    mvc.perform(get("/api/projects/999999")).andExpect(status().isNotFound());
  }
}
