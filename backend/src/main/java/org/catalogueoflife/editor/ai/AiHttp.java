package org.catalogueoflife.editor.ai;

import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Shared HTTP plumbing for the RestClient-based provider adapters (OpenAI, Google, Mistral). Fixed
// provider hosts, so no SSRF surface; a generous read timeout because LLM calls are slow. A provider
// error/timeout degrades to 502 rather than leaking the raw failure.
final class AiHttp {

  private AiHttp() {}

  static RestClient client(String baseUrl) {
    var requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(5));
    requestFactory.setReadTimeout(Duration.ofSeconds(60));
    return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
  }

  static JsonNode postJson(RestClient http, String uri, Map<String, String> headers, Object body,
      ObjectMapper mapper, Object... uriVars) {
    try {
      RestClient.RequestBodySpec spec = http.post().uri(uri, uriVars);
      headers.forEach(spec::header);
      String response = spec.body(body).retrieve().body(String.class);
      if (response == null) {
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "empty AI response");
      }
      return mapper.readTree(response);
    } catch (RestClientResponseException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
          "AI provider request failed (" + e.getStatusCode() + ")");
    } catch (RestClientException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI provider unavailable");
    }
  }

  // OpenAI-compatible chat/completions response (OpenAI and Mistral share this shape).
  static AiResult fromOpenAiChat(JsonNode response, ObjectMapper mapper) {
    String content = str(response.path("choices").path(0).path("message").path("content"));
    int in = response.path("usage").path("prompt_tokens").asInt();
    int out = response.path("usage").path("completion_tokens").asInt();
    return new AiResult(AiJson.parse(mapper, content), in, out);
  }

  static String str(JsonNode n) {
    return n == null || n.isMissingNode() || n.isNull() ? "" : n.asString();
  }

  static String requireKey(AiProperties props, Provider provider) {
    String key = props.getApiKeys().get(provider);
    if (key == null || key.isBlank()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "no API key configured for " + provider);
    }
    return key;
  }
}
