package org.catalogueoflife.editor.admin;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "fieldsAdmin")
class AdminUserFieldsIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired AppUserMapper mapper;

  @Test
  void listExposesEmailAndApplicationNote() throws Exception {
    if (users.requireByUsernameOrNull("fieldsAdmin") == null) users.createLocal("fieldsAdmin", "pw", "F");
    users.markAdmin("fieldsAdmin");

    AppUser applicant = users.upsertFromOrcid("0000-0002-3333-0003", "Fld Applicant", "F", "A");
    applicant.setEmail("fields@example.org");
    applicant.setApplicationNote("beetle curator");
    mapper.update(applicant);

    mvc.perform(get("/api/admin/users"))
       .andExpect(status().isOk())
       .andExpect(content().string(containsString("fields@example.org")))
       .andExpect(content().string(containsString("beetle curator")));
  }
}
