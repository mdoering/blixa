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
}
