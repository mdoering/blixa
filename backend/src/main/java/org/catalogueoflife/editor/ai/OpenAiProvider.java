package org.catalogueoflife.editor.ai;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// OpenAI adapter -- POST /v1/chat/completions with response_format=json_object, then parse the JSON
// content into AiSuggestions. Like every adapter it makes a live call, so it's never test-exercised;
// the pipeline is verified against a @MockitoBean provider. The model comes from AI config.
@Component
public class OpenAiProvider implements LlmProvider {

  private final AiProperties props;
  private final ObjectMapper mapper;
  private final RestClient http;

  public OpenAiProvider(AiProperties props, ObjectMapper mapper) {
    this.props = props;
    this.mapper = mapper;
    this.http = AiHttp.client("https://api.openai.com");
  }

  @Override
  public Provider id() {
    return Provider.OPENAI;
  }

  @Override
  public AiResult suggest(AiTaxonContext context, String model) {
    String key = AiHttp.requireKey(props, Provider.OPENAI);
    Map<String, Object> body = Map.of(
        "model", model,
        "messages", List.of(Map.of("role", "user", "content", AiPrompts.userPrompt(context))),
        "response_format", Map.of("type", "json_object"));
    JsonNode response = AiHttp.postJson(http, "/v1/chat/completions",
        Map.of("Authorization", "Bearer " + key), body, mapper);
    return AiHttp.fromOpenAiChat(response, mapper);
  }
}
