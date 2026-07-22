package org.catalogueoflife.editor.ai;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Google (Gemini) adapter -- POST models/{model}:generateContent with responseMimeType application/json
// (the key is a query param, not a header). Never test-exercised (live call); model from AI config.
@Component
public class GoogleProvider implements LlmProvider {

  private final AiProperties props;
  private final ObjectMapper mapper;
  private final RestClient http;

  public GoogleProvider(AiProperties props, ObjectMapper mapper) {
    this.props = props;
    this.mapper = mapper;
    this.http = AiHttp.client("https://generativelanguage.googleapis.com");
  }

  @Override
  public Provider id() {
    return Provider.GOOGLE;
  }

  @Override
  public AiResult suggest(AiTaxonContext context, String model) {
    String key = AiHttp.requireKey(props, Provider.GOOGLE);
    Map<String, Object> body = Map.of(
        "contents", List.of(Map.of("parts", List.of(Map.of("text", AiPrompts.userPrompt(context))))),
        "generationConfig", Map.of("responseMimeType", "application/json"));
    JsonNode response = AiHttp.postJson(http, "/v1beta/models/{model}:generateContent?key={key}",
        Map.of(), body, mapper, model, key);
    String content = AiHttp.str(response.path("candidates").path(0)
        .path("content").path("parts").path(0).path("text"));
    int in = response.path("usageMetadata").path("promptTokenCount").asInt();
    int out = response.path("usageMetadata").path("candidatesTokenCount").asInt();
    return new AiResult(AiJson.parse(mapper, content), in, out);
  }
}
