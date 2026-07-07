package org.catalogueoflife.editor.user;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AppUserService implements UserDetailsService {

  private final AppUserMapper mapper;
  private final PasswordEncoder encoder;

  public AppUserService(AppUserMapper mapper, PasswordEncoder encoder) {
    this.mapper = mapper;
    this.encoder = encoder;
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
