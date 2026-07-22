package org.catalogueoflife.editor.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AiConfigServiceTest {

  private static AiProperties props(Provider def, String model, Provider keyProvider, String key) {
    AiProperties p = new AiProperties();
    p.setDefaultProvider(def);
    p.setDefaultModel(model);
    if (keyProvider != null) p.getApiKeys().put(keyProvider, key);
    return p;
  }

  private static AiConfigService svc(AiProperties p) {
    return new AiConfigService(p, null); // resolve() must not touch ProjectService
  }

  @Test
  void availableWhenProviderModelAndKeyPresent() {
    AiConfigResponse r =
        svc(props(Provider.ANTHROPIC, "claude-opus-4-8", Provider.ANTHROPIC, "sk-x")).resolve();
    assertThat(r.available()).isTrue();
    assertThat(r.provider()).isEqualTo("anthropic");
    assertThat(r.model()).isEqualTo("claude-opus-4-8");
  }

  @Test
  void notAvailableWhenKeyMissing() {
    AiConfigResponse r = svc(props(Provider.ANTHROPIC, "claude-opus-4-8", null, null)).resolve();
    assertThat(r.available()).isFalse();
    // still reports the resolved provider/model so settings can show the default
    assertThat(r.provider()).isEqualTo("anthropic");
    assertThat(r.model()).isEqualTo("claude-opus-4-8");
  }

  @Test
  void notAvailableWhenNoDefaultProvider() {
    AiConfigResponse r = svc(props(null, null, null, null)).resolve();
    assertThat(r.available()).isFalse();
    assertThat(r.provider()).isNull();
    assertThat(r.model()).isNull();
  }

  @Test
  void notAvailableWhenKeyIsBlank() {
    AiConfigResponse r =
        svc(props(Provider.OPENAI, "gpt-x", Provider.OPENAI, "   ")).resolve();
    assertThat(r.available()).isFalse();
    assertThat(r.provider()).isEqualTo("openai");
  }

  @Test
  void perProviderModelOverridesTheDefaultModel() {
    AiProperties p = props(Provider.ANTHROPIC, "fallback-model", Provider.ANTHROPIC, "sk-x");
    p.getModels().put(Provider.ANTHROPIC, "claude-opus-4-8");
    AiConfigResponse r = svc(p).resolve();
    assertThat(r.available()).isTrue();
    assertThat(r.model()).isEqualTo("claude-opus-4-8");
  }

  @Test
  void fallsBackToDefaultModelWhenActiveProviderHasNoModel() {
    AiProperties p = props(Provider.ANTHROPIC, "claude-default", Provider.ANTHROPIC, "sk-x");
    p.getModels().put(Provider.OPENAI, "gpt-5"); // a model for a different provider
    AiConfigResponse r = svc(p).resolve();
    assertThat(r.model()).isEqualTo("claude-default");
  }

  @Test
  void notAvailableWhenNoModelForTheActiveProviderAndNoDefault() {
    AiProperties p = props(Provider.OPENAI, null, Provider.OPENAI, "sk-x"); // no default, no openai model
    p.getModels().put(Provider.ANTHROPIC, "claude-opus-4-8");
    AiConfigResponse r = svc(p).resolve();
    assertThat(r.available()).isFalse();
    assertThat(r.model()).isNull();
  }
}
