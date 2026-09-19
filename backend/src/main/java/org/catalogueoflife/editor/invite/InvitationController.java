package org.catalogueoflife.editor.invite;

import java.util.List;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.invite.dto.CreateInvitationRequest;
import org.catalogueoflife.editor.invite.dto.InvitationResponse;
import org.catalogueoflife.editor.user.AppUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// Owner-only management of a project's email invitations (all gated via ProjectService.requireOwner:
// non-owner member 403, non-member 404). The email goes out here, after the service's transaction
// has committed -- same pattern as AdminUserController.setState.
@RestController
@RequestMapping("/api/projects/{pid}/invitations")
public class InvitationController {

  private final InvitationService service;
  private final InvitationNotifier notifier;
  private final CurrentUser currentUser;

  public InvitationController(InvitationService service, InvitationNotifier notifier, CurrentUser currentUser) {
    this.service = service;
    this.notifier = notifier;
    this.currentUser = currentUser;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public InvitationResponse create(@PathVariable int pid, @RequestBody CreateInvitationRequest req) {
    AppUser me = currentUser.require();
    ProjectInvitation inv = service.create(me.getId(), pid, req);
    notifier.sendInvitation(inv, me);
    return toResponse(inv);
  }

  @GetMapping
  public List<InvitationResponse> list(@PathVariable int pid) {
    return service.listPending(currentUser.require().getId(), pid).stream().map(this::toResponse).toList();
  }

  @PostMapping("/{id}/resend")
  public InvitationResponse resend(@PathVariable int pid, @PathVariable int id) {
    AppUser me = currentUser.require();
    ProjectInvitation inv = service.resend(me.getId(), pid, id);
    notifier.sendInvitation(inv, me);
    return toResponse(inv);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revoke(@PathVariable int pid, @PathVariable int id) {
    service.revoke(currentUser.require().getId(), pid, id);
  }

  private InvitationResponse toResponse(ProjectInvitation inv) {
    return InvitationResponse.of(inv, notifier.acceptUrl(inv.getToken()));
  }
}
