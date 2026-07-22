package org.catalogueoflife.editor.ai;

import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Backend-only AI configuration (never in the DB or exposed to the frontend). Bound from {@code
 * coldp.ai.*}:
 *
 * <pre>
 *   coldp.ai.default-provider: anthropic
 *   coldp.ai.default-model: claude-opus-4-8
 *   coldp.ai.api-keys.anthropic: ${ANTHROPIC_API_KEY:}
 *   coldp.ai.api-keys.openai:    ${OPENAI_API_KEY:}
 * </pre>
 *
 * <p>The installation-wide default provider/model is the only config in v1 (a per-project override
 * is a later increment). A provider is "available" only when it has a non-blank key here.
 */
@Component
@ConfigurationProperties("coldp.ai")
public class AiProperties {

  private Provider defaultProvider;
  private String defaultModel;
  private Map<Provider, String> apiKeys = new EnumMap<>(Provider.class);

  public Provider getDefaultProvider() {
    return defaultProvider;
  }

  public void setDefaultProvider(Provider defaultProvider) {
    this.defaultProvider = defaultProvider;
  }

  public String getDefaultModel() {
    return defaultModel;
  }

  public void setDefaultModel(String defaultModel) {
    this.defaultModel = defaultModel;
  }

  public Map<Provider, String> getApiKeys() {
    return apiKeys;
  }

  public void setApiKeys(Map<Provider, String> apiKeys) {
    this.apiKeys = apiKeys;
  }

  /** True when {@code provider} has a non-blank API key configured. */
  public boolean hasKey(Provider provider) {
    String key = provider == null ? null : apiKeys.get(provider);
    return key != null && !key.isBlank();
  }
}
