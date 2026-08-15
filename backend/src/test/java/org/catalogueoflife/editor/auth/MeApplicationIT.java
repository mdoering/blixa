package org.catalogueoflife.editor.auth;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.notify.EmailService;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeApplicationIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired AppUserMapper mapper;
  @MockitoBean EmailService email;

  @Test
  void pendingUserAppliesAndAdminsAreNotifiedOnce() throws Exception {
    // an active admin with an email is the notification target
    if (users.requireByUsernameOrNull("appAdmin") == null) users.createLocal("appAdmin", "pw", "Adm");
    users.markAdmin("appAdmin");
    AppUser admin = users.requireByUsernameOrNull("appAdmin");
    admin.setEmail("appadmin@example.org");
    mapper.update(admin);

    // a fresh ORCID self-signup, PENDING with no email
    String orcid = "0000-0002-9999-0001";
    users.upsertFromOrcid(orcid, "New Applicant", "New", "Applicant");

    // blank email -> 400
    mvc.perform(put("/api/me/application").with(user(orcid)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"\",\"note\":\"hi\"}"))
       .andExpect(status().isBadRequest());

    // valid application -> 200 (NOT 403: proves the ActiveUserFilter allow-list), email stored
    mvc.perform(put("/api/me/application").with(user(orcid)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"applicant@example.org\",\"note\":\"I curate beetles\"}"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.email").value("applicant@example.org"));

    verify(email).send(eq("appadmin@example.org"), contains("access request"), anyString());

    // editing the application again does NOT re-notify admins
    mvc.perform(put("/api/me/application").with(user(orcid)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"applicant2@example.org\",\"note\":\"still beetles\"}"))
       .andExpect(status().isOk());
    verify(email, times(1)).send(eq("appadmin@example.org"), contains("access request"), anyString());
  }
}
