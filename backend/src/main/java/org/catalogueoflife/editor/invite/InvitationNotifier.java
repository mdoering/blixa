package org.catalogueoflife.editor.invite;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.catalogueoflife.editor.notify.EmailService;
import org.catalogueoflife.editor.user.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// The project-invitation email. Sent To the invitee, with the inviting owner on CC and as Reply-To
// (so an invitee's reply reaches a person, not the no-reply sender). Best-effort: never throws.
@Service
public class InvitationNotifier {

  private static final Logger log = LoggerFactory.getLogger(InvitationNotifier.class);
  private static final DateTimeFormatter EXPIRY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

  private final EmailService email;
  private final String baseUrl;

  public InvitationNotifier(EmailService email, @Value("${coldp.mail.base-url:}") String baseUrl) {
    this.email = email;
    this.baseUrl = baseUrl;
  }

  public String acceptUrl(String token) {
    return baseUrl + "/invite/" + token;
  }

  public void sendInvitation(ProjectInvitation inv, AppUser inviter) {
    try {
      String name = inviterName(inviter);
      String title = inv.getProjectTitle();
      StringBuilder body = new StringBuilder()
          .append(name).append(" has invited you to join the project \"").append(title)
          .append("\" on Blixa, the collaborative editor for taxonomic checklists, as ")
          .append(withArticle(inv.getRole())).append(".\n\n");
      if (inv.getMessage() != null && !inv.getMessage().isBlank()) {
        body.append(name).append(" wrote:\n").append(inv.getMessage()).append("\n\n");
      }
      body.append("To accept, open the link below and sign in with your ORCID iD. If you don't have "
              + "one yet, you can register for free at orcid.org during sign-in.\n\n")
          .append(acceptUrl(inv.getToken())).append("\n\n")
          .append("This invitation expires on ").append(EXPIRY.format(inv.getExpiresAt()))
          .append(". If you weren't expecting it, you can ignore this email.\n");
      String cc = inviter == null ? null : inviter.getEmail();
      email.send(inv.getEmail(), cc, cc, "You're invited to join \"" + title + "\" on Blixa",
          body.toString());
    } catch (Exception e) {
      log.warn("failed to send invitation email to {}: {}", inv.getEmail(), e.toString());
    }
  }

  static String withArticle(String role) {
    return ("owner".equals(role) || "editor".equals(role) ? "an " : "a ") + role;
  }

  private static String inviterName(AppUser u) {
    if (u == null) return "A Blixa user";
    return u.getDisplayName() == null || u.getDisplayName().isBlank() ? u.getUsername() : u.getDisplayName();
  }
}
