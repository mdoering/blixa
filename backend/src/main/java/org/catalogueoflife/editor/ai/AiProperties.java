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
 *   coldp.ai.default-model: claude-opus-4-8   # fallback when a provider has no models entry
 *   coldp.ai.models.anthropic: claude-opus-4-8
 *   coldp.ai.models.openai:    gpt-5
 *   coldp.ai.api-keys.anthropic: ${ANTHROPIC_API_KEY:}
 *   coldp.ai.api-keys.openai:    ${OPENAI_API_KEY:}
 * </pre>
 *
 * <p>The active provider uses {@link #modelFor its own model} (falling back to {@code default-model}),
 * so switching provider picks up that provider's model. Installation-wide config only in v1 (a
 * per-project override is a later increment). A provider is "available" only when it has a non-blank
 * key here.
 */
@Component
@ConfigurationProperties("coldp.ai")
public class AiProperties {

  private Provider defaultProvider;
  private String defaultModel;
  private Map<Provider, String> apiKeys = new EnumMap<>(Provider.class);
  private Map<Provider, String> models = new EnumMap<>(Provider.class);

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

  public Map<Provider, String> getModels() {
    return models;
  }

  public void setModels(Map<Provider, String> models) {
    this.models = models;
  }

  /** True when {@code provider} has a non-blank API key configured. */
  public boolean hasKey(Provider provider) {
    String key = provider == null ? null : apiKeys.get(provider);
    return key != null && !key.isBlank();
  }

  /** The model to use for {@code provider}: its own configured model, else the {@code defaultModel}. */
  public String modelFor(Provider provider) {
    String model = provider == null ? null : models.get(provider);
    return model != null && !model.isBlank() ? model : defaultModel;
  }
}
