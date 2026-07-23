package org.catalogueoflife.editor.bhl;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// Backend-only BHL configuration. `coldp.bhl.api-key` (a free key from biodiversitylibrary.org) is
// required to reach the BHL API; without it the feature is unavailable and the UI hides its
// affordances. Never exposed to the frontend.
@Component
@ConfigurationProperties("coldp.bhl")
public class BhlProperties {

  private String apiKey;

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public boolean hasKey() {
    return apiKey != null && !apiKey.isBlank();
  }
}
