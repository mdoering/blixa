package org.catalogueoflife.editor.invite;

import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.invite.dto.AcceptInvitationResponse;
import org.catalogueoflife.editor.invite.dto.InvitationPreview;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

// The invitee's side, addressed by the emailed token. The preview is public (/api/public/**:
// permitAll + CSRF-exempt) so the accept page can show what the link is for before sign-in. Accept
// needs a login; ActiveUserFilter exempts /api/invitations/ so a brand-new PENDING account can call it.
@RestController
public class InvitationTokenController {

  private final InvitationService service;
  private final CurrentUser currentUser;

  public InvitationTokenController(InvitationService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @GetMapping("/api/public/invitations/{token}")
  public InvitationPreview preview(@PathVariable String token) {
    return service.preview(token);
  }

  @PostMapping("/api/invitations/{token}/accept")
  public AcceptInvitationResponse accept(@PathVariable String token) {
    return new AcceptInvitationResponse(service.accept(currentUser.require().getId(), token));
  }
}
