package org.catalogueoflife.editor.ai;

// The LLM providers the AI-assisted curation feature can be configured to use. The active provider
// (installation default; per-project override is a later increment) plus its API key live in backend
// config only -- see AiProperties. Wire form is the lower-cased name (e.g. "anthropic").
public enum Provider {
  ANTHROPIC,
  OPENAI,
  GOOGLE,
  MISTRAL
}
