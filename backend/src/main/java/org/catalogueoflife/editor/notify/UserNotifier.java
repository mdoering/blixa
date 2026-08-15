package org.catalogueoflife.editor.notify;

import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// Emails around the account lifecycle (application submitted, application approved). Best-effort:
// never throws, so a notification problem can't break the triggering action.
@Service
public class UserNotifier {

  private final AppUserMapper users;
  private final EmailService email;
  private final String baseUrl;

  public UserNotifier(AppUserMapper users, EmailService email,
      @Value("${coldp.mail.base-url:}") String baseUrl) {
    this.users = users;
    this.email = email;
    this.baseUrl = baseUrl;
  }

  public void notifyApproved(AppUser user) {
    if (user == null || user.getEmail() == null || user.getEmail().isBlank()) return;
    email.send(user.getEmail(), "Your Blixa account has been approved",
        "Your Blixa account has been approved. You can now sign in:\n\n" + baseUrl + "/signin");
  }

  public void notifyAdminsOfApplication(AppUser applicant) {
    try {
      String name = applicant.getDisplayName() == null || applicant.getDisplayName().isBlank()
          ? applicant.getUsername() : applicant.getDisplayName();
      String note = applicant.getApplicationNote() == null || applicant.getApplicationNote().isBlank()
          ? "(no message)" : applicant.getApplicationNote();
      String subject = "New Blixa access request from " + name;
      String body = "A new access request is awaiting review.\n\n"
          + "Name: " + name + "\n"
          + "ORCID: " + (applicant.getOrcid() == null ? "—" : applicant.getOrcid()) + "\n"
          + "Email: " + (applicant.getEmail() == null ? "—" : applicant.getEmail()) + "\n"
          + "Message: " + note + "\n\n" + baseUrl + "/admin/users";
      for (AppUser admin : users.findActiveAdminsWithEmail()) {
        email.send(admin.getEmail(), subject, body);
      }
    } catch (Exception e) {
      // best-effort: swallow any failure so the triggering action still succeeds
    }
  }
}
