package org.catalogueoflife.editor.ai;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Mistral adapter -- Mistral's chat/completions API is OpenAI-compatible, so this mirrors
// OpenAiProvider against api.mistral.ai. Never test-exercised (live call); model from AI config.
@Component
public class MistralProvider implements LlmProvider {

  private final AiProperties props;
  private final ObjectMapper mapper;
  private final RestClient http;

  public MistralProvider(AiProperties props, ObjectMapper mapper) {
    this.props = props;
    this.mapper = mapper;
    this.http = AiHttp.client("https://api.mistral.ai");
  }

  @Override
  public Provider id() {
    return Provider.MISTRAL;
  }

  @Override
  public AiResult suggest(AiTaxonContext context, String model) {
    String key = AiHttp.requireKey(props, Provider.MISTRAL);
    Map<String, Object> body = Map.of(
        "model", model,
        "messages", List.of(Map.of("role", "user", "content", AiPrompts.userPrompt(context))),
        "response_format", Map.of("type", "json_object"));
    JsonNode response = AiHttp.postJson(http, "/v1/chat/completions",
        Map.of("Authorization", "Bearer " + key), body, mapper);
    return AiHttp.fromOpenAiChat(response, mapper);
  }
}
