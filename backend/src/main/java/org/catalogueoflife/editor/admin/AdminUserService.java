package org.catalogueoflife.editor.admin;

import java.util.List;
import java.util.Locale;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.catalogueoflife.editor.user.UserState;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// Global-admin-only user administration: list users, approve/disable (state), and grant/revoke the
// admin flag. Guarded so an admin can't lock themselves out (self-disable / self-demote).
@Service
public class AdminUserService {

  private final AppUserMapper users;

  public AdminUserService(AppUserMapper users) {
    this.users = users;
  }

  public List<AppUser> list(int actorId) {
    requireAdmin(actorId);
    return users.findAll();
  }

  @Transactional
  public AppUser setState(int actorId, int userId, String stateRaw) {
    requireAdmin(actorId);
    UserState state = parseState(stateRaw);
    AppUser target = requireUser(userId);
    if (userId == actorId && state != UserState.ACTIVE) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "you can't deactivate your own account");
    }
    target.setState(state.name());
    users.update(target);
    return target;
  }

  @Transactional
  public AppUser setAdmin(int actorId, int userId, boolean admin) {
    requireAdmin(actorId);
    AppUser target = requireUser(userId);
    if (userId == actorId && !admin) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "you can't remove your own admin role");
    }
    target.setAdmin(admin);
    users.update(target);
    return target;
  }

  private AppUser requireAdmin(int actorId) {
    AppUser actor = users.findById(actorId);
    if (actor == null || !actor.isAdmin()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin required");
    }
    return actor;
  }

  private AppUser requireUser(int userId) {
    AppUser u = users.findById(userId);
    if (u == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found");
    }
    return u;
  }

  private static UserState parseState(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "state is required");
    }
    try {
      return UserState.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown state: " + raw);
    }
  }
}
