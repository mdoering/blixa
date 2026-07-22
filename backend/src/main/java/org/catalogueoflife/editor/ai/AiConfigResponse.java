package org.catalogueoflife.editor.ai;

// What the frontend needs to decide whether to show the AI affordance and what to label it -- the
// resolved provider + model and whether AI is usable (a configured provider WITH a backend key).
// Never carries a key. See AiConfigService#resolve and AiConfigController.
public record AiConfigResponse(boolean available, String provider, String model) {}
