package org.catalogueoflife.editor.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConfigController {

  // ORCID is "configured" iff the client-id is not the sentinel default (see application.yml).
  @Value("${spring.security.oauth2.client.registration.orcid.client-id:unconfigured}")
  private String orcidClientId;

  public record ConfigResponse(boolean orcidEnabled) {}

  @GetMapping("/api/config")
  public ConfigResponse config() {
    return new ConfigResponse(!"unconfigured".equals(orcidClientId));
  }
}
