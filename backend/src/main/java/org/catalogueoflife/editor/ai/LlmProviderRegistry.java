package org.catalogueoflife.editor.ai;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

// Looks up the LlmProvider adapter for a resolved Provider. Resolves lazily over the injected bean
// list (rather than caching a map at construction) so a @MockitoBean adapter -- whose id() is only
// stubbed inside the test method, after the context is built -- is still found.
@Component
public class LlmProviderRegistry {

  private final List<LlmProvider> providers;

  public LlmProviderRegistry(List<LlmProvider> providers) {
    this.providers = providers;
  }

  public LlmProvider require(Provider provider) {
    return providers.stream()
        .filter(p -> p.id() == provider)
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "no AI provider configured for " + provider));
  }
}
