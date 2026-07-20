package org.catalogueoflife.editor.user;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AppUserService implements UserDetailsService {

  // A username must be mention-friendly (@username): start alphanumeric, then alphanumeric/_/-,
  // at least 2 chars. Keeps handles usable as inline mentions in discussions.
  private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]+");

  private final AppUserMapper mapper;
  private final PasswordEncoder encoder;

  public AppUserService(AppUserMapper mapper, PasswordEncoder encoder) {
    this.mapper = mapper;
    this.encoder = encoder;
  }

  // Let a user pick a custom, unique username (their display handle; also the login name for local
  // accounts). Rejects a blank/invalid form (400) or a name already taken by someone else (409).
  @Transactional
  public AppUser updateUsername(int userId, String rawUsername) {
    String username = rawUsername == null ? "" : rawUsername.trim();
    if (!USERNAME.matcher(username).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "username must be 2+ characters of letters, digits, _ or - and start alphanumeric");
    }
    AppUser existing = mapper.findByUsername(username);
    if (existing != null && existing.getId() != userId) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "username already taken");
    }
    AppUser me = mapper.findById(userId);
    if (me == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found");
    }
    me.setUsername(username);
    mapper.update(me);
    return me;
  }

  public AppUser createLocal(String username, String rawPassword, String displayName) {
    AppUser u = new AppUser();
    u.setUsername(username);
    u.setDisplayName(displayName);
    u.setPasswordHash(encoder.encode(rawPassword));
    mapper.insert(u);
    return u;
  }

  public AppUser upsertFromOrcid(String orcid, String displayName, String given, String family) {
    AppUser u = mapper.findByOrcid(orcid);
    if (u == null) {
      u = new AppUser();
      u.setOrcid(orcid);
      u.setUsername(orcid);
      u.setDisplayName(displayName);
      u.setGiven(given);
      u.setFamily(family);
      mapper.insert(u);
    } else {
      u.setDisplayName(displayName);
      u.setGiven(given);
      u.setFamily(family);
      mapper.update(u);
    }
    return u;
  }

  public AppUser requireByUsername(String username) {
    AppUser u = mapper.findByUsername(username);
    if (u == null) {
      throw new UsernameNotFoundException(username);
    }
    return u;
  }

  public AppUser requireByUsernameOrNull(String username) {
    return mapper.findByUsername(username);
  }

  @Override
  public UserDetails loadUserByUsername(String username) {
    AppUser u = mapper.findByUsername(username);
    if (u == null || u.getPasswordHash() == null) {
      throw new UsernameNotFoundException(username);
    }
    return new User(u.getUsername(), u.getPasswordHash(),
        List.of(new SimpleGrantedAuthority("ROLE_USER")));
  }
}
