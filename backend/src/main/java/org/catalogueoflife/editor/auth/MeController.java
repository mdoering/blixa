package org.catalogueoflife.editor.auth;

import java.util.Map;
import org.catalogueoflife.editor.user.AppUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

  private final CurrentUser currentUser;

  public MeController(CurrentUser currentUser) {
    this.currentUser = currentUser;
  }

  @GetMapping("/api/me")
  public Map<String, Object> me() {
    AppUser u = currentUser.require();
    return Map.of(
        "id", u.getId(),
        "username", u.getUsername(),
        "orcid", u.getOrcid() == null ? "" : u.getOrcid(),
        "displayName", u.getDisplayName() == null ? "" : u.getDisplayName());
  }
}
