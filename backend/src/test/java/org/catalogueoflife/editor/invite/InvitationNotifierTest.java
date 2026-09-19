package org.catalogueoflife.editor.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.catalogueoflife.editor.notify.EmailService;
import org.catalogueoflife.editor.user.AppUser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InvitationNotifierTest {

  private final EmailService email = mock(EmailService.class);
  private final InvitationNotifier notifier = new InvitationNotifier(email, "https://blixa.example.org");

  private static ProjectInvitation inv(String role, String message) {
    ProjectInvitation inv = new ProjectInvitation();
    inv.setEmail("invitee@example.org");
    inv.setRole(role);
    inv.setMessage(message);
    inv.setToken("tok123");
    inv.setProjectTitle("Beetles of Europe");
    inv.setExpiresAt(OffsetDateTime.of(2026, 10, 19, 12, 0, 0, 0, ZoneOffset.UTC));
    return inv;
  }

  private static AppUser owner(String mail) {
    AppUser u = new AppUser();
    u.setUsername("olga");
    u.setDisplayName("Olga Owner");
    u.setEmail(mail);
    return u;
  }

  @Test
  void sendsToInviteeWithOwnerAsCcAndReplyTo() {
    notifier.sendInvitation(inv("editor", null), owner("olga@example.org"));
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(email).send(eq("invitee@example.org"), eq("olga@example.org"), eq("olga@example.org"),
        eq("You're invited to join \"Beetles of Europe\" on Blixa"), body.capture());
    assertThat(body.getValue())
        .contains("Olga Owner has invited you to join the project \"Beetles of Europe\" on Blixa")
        .contains("as an editor.")
        .contains("sign in with your ORCID iD")
        .contains("https://blixa.example.org/invite/tok123")
        .contains("This invitation expires on 19 October 2026.")
        .doesNotContain("wrote:");
  }

  @Test
  void includesPersonalMessageAndFallsBackToUsernameWithoutCc() {
    AppUser inviter = owner(null);
    inviter.setDisplayName(" ");
    notifier.sendInvitation(inv("viewer", "Please help with the weevils."), inviter);
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(email).send(eq("invitee@example.org"), isNull(), isNull(),
        eq("You're invited to join \"Beetles of Europe\" on Blixa"), body.capture());
    assertThat(body.getValue())
        .startsWith("olga has invited you")
        .contains("as a viewer.")
        .contains("olga wrote:\nPlease help with the weevils.\n\n");
  }

  @Test
  void articles() {
    assertThat(InvitationNotifier.withArticle("owner")).isEqualTo("an owner");
    assertThat(InvitationNotifier.withArticle("editor")).isEqualTo("an editor");
    assertThat(InvitationNotifier.withArticle("viewer")).isEqualTo("a viewer");
  }

  @Test
  void acceptUrl() {
    assertThat(notifier.acceptUrl("abc")).isEqualTo("https://blixa.example.org/invite/abc");
  }
}
