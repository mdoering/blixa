package org.catalogueoflife.editor.auth;

import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class CurrentUser {

  private final AppUserMapper users;

  public CurrentUser(AppUserMapper users) {
    this.users = users;
  }

  public AppUser require() {
    Authentication a = SecurityContextHolder.getContext().getAuthentication();
    if (a == null || !a.isAuthenticated() || "anonymousUser".equals(a.getName())) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    AppUser u = users.findByUsername(a.getName());
    if (u == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    return u;
  }
}
