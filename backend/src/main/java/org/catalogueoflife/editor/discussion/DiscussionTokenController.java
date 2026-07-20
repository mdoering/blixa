package org.catalogueoflife.editor.discussion;

import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.discussion.dto.TokenResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// Editor-only management of the project's external-submission API token. A distinct path (not under
// /discussions) so it can't collide with /discussions/{id}.
@RestController
@RequestMapping("/api/projects/{pid}/discussion-token")
public class DiscussionTokenController {

  private final DiscussionApiTokenService service;
  private final CurrentUser currentUser;

  public DiscussionTokenController(DiscussionApiTokenService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @GetMapping
  public TokenResponse get(@PathVariable int pid) {
    return new TokenResponse(service.get(currentUser.require().getId(), pid));
  }

  @PostMapping
  public TokenResponse generate(@PathVariable int pid) {
    return new TokenResponse(service.generate(currentUser.require().getId(), pid));
  }

  @DeleteMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revoke(@PathVariable int pid) {
    service.revoke(currentUser.require().getId(), pid);
  }
}
