package org.catalogueoflife.editor.auth;

import java.util.Map;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

  private final CurrentUser currentUser;
  private final AppUserService users;

  public MeController(CurrentUser currentUser, AppUserService users) {
    this.currentUser = currentUser;
    this.users = users;
  }

  @GetMapping("/api/me")
  public Map<String, Object> me() {
    return meMap(currentUser.require());
  }

  // Let the signed-in user choose a custom, unique username.
  @PutMapping("/api/me/username")
  public Map<String, Object> updateUsername(@RequestBody Map<String, String> body) {
    AppUser me = currentUser.require();
    return meMap(users.updateUsername(me.getId(), body.get("username")));
  }

  private static Map<String, Object> meMap(AppUser u) {
    return Map.of(
        "id", u.getId(),
        "username", u.getUsername(),
        "orcid", u.getOrcid() == null ? "" : u.getOrcid(),
        "displayName", u.getDisplayName() == null ? "" : u.getDisplayName(),
        "admin", u.isAdmin(),
        "state", u.getState() == null ? "ACTIVE" : u.getState());
  }
}
