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
  private final org.catalogueoflife.editor.notify.UserNotifier userNotifier;

  public MeController(CurrentUser currentUser, AppUserService users,
      org.catalogueoflife.editor.notify.UserNotifier userNotifier) {
    this.currentUser = currentUser;
    this.users = users;
    this.userNotifier = userNotifier;
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

  // Let the signed-in user set/update their contact email.
  @PutMapping("/api/me/email")
  public Map<String, Object> updateEmail(@RequestBody Map<String, String> body) {
    AppUser me = currentUser.require();
    return meMap(users.updateEmail(me.getId(), body.get("email")));
  }

  // A PENDING applicant submits/updates their required email + optional message. Admins are emailed
  // once, on the first completed application.
  @PutMapping("/api/me/application")
  public Map<String, Object> submitApplication(@RequestBody Map<String, String> body) {
    AppUser me = currentUser.require();
    AppUserService.ApplicationResult result =
        users.submitApplication(me.getId(), body.get("email"), body.get("note"));
    if (result.notifyAdmins()) {
      userNotifier.notifyAdminsOfApplication(result.user());
    }
    return meMap(result.user());
  }

  private static Map<String, Object> meMap(AppUser u) {
    return Map.of(
        "id", u.getId(),
        "username", u.getUsername(),
        "email", u.getEmail() == null ? "" : u.getEmail(),
        "orcid", u.getOrcid() == null ? "" : u.getOrcid(),
        "displayName", u.getDisplayName() == null ? "" : u.getDisplayName(),
        "admin", u.isAdmin(),
        "state", u.getState() == null ? "ACTIVE" : u.getState());
  }
}
