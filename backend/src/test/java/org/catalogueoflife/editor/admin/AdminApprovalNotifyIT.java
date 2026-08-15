package org.catalogueoflife.editor.admin;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "apprAdmin")
class AdminApprovalNotifyIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired AppUserMapper mapper;
  @MockitoBean EmailService email;

  private void postState(int id, String state) throws Exception {
    mvc.perform(post("/api/admin/users/" + id + "/state").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"" + state + "\"}"))
       .andExpect(status().isOk());
  }

  @Test
  void approvalEmailsApplicantButReactivationDoesNot() throws Exception {
    if (users.requireByUsernameOrNull("apprAdmin") == null) users.createLocal("apprAdmin", "pw", "A");
    users.markAdmin("apprAdmin");

    // PENDING applicant with an email -> approving emails them
    AppUser applicant = users.upsertFromOrcid("0000-0002-7777-0002", "Appl", "A", "Ppl");
    applicant.setEmail("appl@example.org");
    mapper.update(applicant);
    postState(applicant.getId(), "ACTIVE");
    verify(email).send(eq("appl@example.org"), contains("approved"), anyString());

    // a separate ACTIVE user with an email, disabled then reactivated -> NOT emailed as "approved"
    AppUser other = users.createLocal("reactivateMe", "pw", "R");
    other.setEmail("react@example.org");
    mapper.update(other);
    postState(other.getId(), "DISABLED");
    postState(other.getId(), "ACTIVE");
    verify(email, never()).send(eq("react@example.org"), contains("approved"), anyString());
  }
}
