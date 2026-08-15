package org.catalogueoflife.editor.notify;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ActiveProfiles("test")
class UserNotifierIT extends AbstractPostgresIT {

  @Autowired UserNotifier notifier;
  @Autowired AppUserService users;
  @Autowired AppUserMapper mapper;
  @MockitoBean EmailService email; // no real SMTP -- verify the notifier's targeting

  private AppUser withEmail(String username, String addr) {
    if (users.requireByUsernameOrNull(username) == null) users.createLocal(username, "pw", username);
    AppUser u = users.requireByUsernameOrNull(username);
    u.setEmail(addr);
    mapper.update(u);
    return u;
  }

  @Test
  void approvedSendsOnlyWhenEmailPresent() {
    AppUser hasEmail = withEmail("appHasEmail", "has@example.org");
    notifier.notifyApproved(hasEmail);
    verify(email).send(eq("has@example.org"), contains("approved"), anyString());

    AppUser noEmail = users.createLocal("appNoEmail", "pw", "No Email");
    notifier.notifyApproved(noEmail); // no email on file -> no send
    // exactly one send total across both calls (the no-email user was skipped)
    verify(email, times(1)).send(anyString(), anyString(), anyString());
  }

  @Test
  void applicationNotifiesOnlyActiveAdminsWithEmail() {
    AppUser admin = withEmail("notifyAdmin", "admin@example.org");
    admin.setAdmin(true);
    mapper.update(admin);
    withEmail("plainWithEmail", "plain@example.org"); // active, not admin -> skipped

    AppUser applicant = new AppUser();
    applicant.setUsername("theApplicant");
    applicant.setDisplayName("The Applicant");
    applicant.setEmail("applicant@example.org");
    applicant.setApplicationNote("I curate beetles");
    applicant.setState("PENDING");
    mapper.insert(applicant);

    notifier.notifyAdminsOfApplication(applicant);
    verify(email).send(eq("admin@example.org"), contains("access request"), anyString());
    verify(email, never()).send(eq("plain@example.org"), anyString(), anyString());
  }
}
