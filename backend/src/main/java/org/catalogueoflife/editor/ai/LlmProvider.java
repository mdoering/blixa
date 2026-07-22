package org.catalogueoflife.editor.ai;

// One LLM backend (Anthropic/OpenAI/Google/Mistral). Each adapter takes the same taxon context and a
// model id, asks the provider for JSON matching the AiSuggestions schema, and returns the validated
// result + token usage. Adapters are @Component beans discovered by LlmProviderRegistry; they are
// @MockitoBean-replaced in tests so the pipeline is verified without a live API call.
public interface LlmProvider {

  Provider id();

  AiResult suggest(AiTaxonContext context, String model);
}
