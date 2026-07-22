package org.catalogueoflife.editor.ai;

// What an LlmProvider returns: the suggestions plus the token usage the provider reported (recorded
// per project in ai_usage). Token counts default to 0 when a provider doesn't report them.
public record AiResult(AiSuggestions suggestions, int inputTokens, int outputTokens) {}
