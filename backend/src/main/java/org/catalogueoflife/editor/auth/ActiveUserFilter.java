package org.catalogueoflife.editor.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.catalogueoflife.editor.user.UserState;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

// Blocks authenticated but non-ACTIVE accounts (PENDING/DISABLED) from the protected API with a 403,
// EXCEPT /api/me and logout -- so a pending user can still load the SPA and see the "awaiting admin
// approval" screen (and log out). The permitAll surface (public/auth/ping/config) is skipped.
public class ActiveUserFilter extends OncePerRequestFilter {

  private static final Set<String> ALLOW =
      Set.of("/api/me", "/api/auth/logout", "/api/ping", "/api/config");

  private final AppUserMapper users;

  public ActiveUserFilter(AppUserMapper users) {
    this.users = users;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String path = req.getRequestURI();
    boolean gated = path.startsWith("/api/") && !ALLOW.contains(path)
        && !path.startsWith("/api/public/") && !path.startsWith("/api/auth/");
    Authentication a = SecurityContextHolder.getContext().getAuthentication();
    if (gated && a != null && a.isAuthenticated() && !(a instanceof AnonymousAuthenticationToken)) {
      AppUser u = users.findByUsername(a.getName());
      if (u != null && !UserState.ACTIVE.name().equals(u.getState())) {
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"account not active\",\"state\":\"" + u.getState() + "\"}");
        return;
      }
    }
    chain.doFilter(req, res);
  }
}
