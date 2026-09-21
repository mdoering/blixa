package org.catalogueoflife.editor.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * The default ORCID authorization request, plus {@code prompt=login} when the SPA asks for it
 * ({@code /oauth2/authorization/orcid?prompt=login}). Signing out of Blixa only ends our session --
 * ORCID's own SSO session lives on, so the next sign-in would silently go straight through as the
 * same person. After an explicit sign-out the SPA requests {@code prompt=login}, which makes ORCID end
 * its session and show its sign-in form. Only that one value is passed through.
 */
public class PromptLoginAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

  private static final String BASE_URI = "/oauth2/authorization";

  private final DefaultOAuth2AuthorizationRequestResolver delegate;

  public PromptLoginAuthorizationRequestResolver(ClientRegistrationRepository clients) {
    this.delegate = new DefaultOAuth2AuthorizationRequestResolver(clients, BASE_URI);
  }

  @Override
  public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
    return withPrompt(request, delegate.resolve(request));
  }

  @Override
  public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
    return withPrompt(request, delegate.resolve(request, clientRegistrationId));
  }

  private static OAuth2AuthorizationRequest withPrompt(HttpServletRequest request,
      OAuth2AuthorizationRequest authRequest) {
    if (authRequest == null || !"login".equals(request.getParameter("prompt"))) {
      return authRequest;
    }
    Map<String, Object> params = new HashMap<>(authRequest.getAdditionalParameters());
    params.put("prompt", "login");
    return OAuth2AuthorizationRequest.from(authRequest).additionalParameters(params).build();
  }
}
