package org.catalogueoflife.editor.user;

import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

@Service
public class OrcidUserService extends OidcUserService {

  private final AppUserService users;

  public OrcidUserService(AppUserService users) {
    this.users = users;
  }

  @Override
  public OidcUser loadUser(OidcUserRequest req) throws OAuth2AuthenticationException {
    OidcUser oidc = super.loadUser(req);
    String orcid = oidc.getSubject();
    String given = oidc.getGivenName();
    String family = oidc.getFamilyName();
    String display = oidc.getFullName() != null ? oidc.getFullName()
        : (given == null ? orcid : (given + (family == null ? "" : " " + family)));
    users.upsertFromOrcid(orcid, display, given, family);
    // Return an OidcUser whose "name" is the ORCID iD, so SecurityContext principal name == orcid,
    // matching how app_user.username is stored for ORCID accounts.
    return new DefaultOidcUser(oidc.getAuthorities(), oidc.getIdToken(), oidc.getUserInfo(), "sub");
  }
}
