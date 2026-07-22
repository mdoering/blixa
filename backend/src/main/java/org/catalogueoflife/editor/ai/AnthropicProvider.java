package org.catalogueoflife.editor.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * Anthropic/Claude adapter -- calls the model through the official Anthropic Java SDK, asking for a
 * single JSON object matching {@link AiSuggestions}, and parses it with the app's Jackson mapper.
 * The API key comes from backend config ({@link AiProperties}); a run is only reached when
 * AiConfigService reports the provider available (key present), so the key is non-blank here.
 *
 * <p>This adapter makes a live external call, so it is never exercised by tests -- the AI pipeline is
 * verified against a @MockitoBean {@link LlmProvider}. Keep it thin.
 */
@Component
public class AnthropicProvider implements LlmProvider {

  private static final long MAX_TOKENS = 8000L;

  private final AiProperties props;
  private final ObjectMapper objectMapper;

  public AnthropicProvider(AiProperties props, ObjectMapper objectMapper) {
    this.props = props;
    this.objectMapper = objectMapper;
  }

  @Override
  public Provider id() {
    return Provider.ANTHROPIC;
  }

  @Override
  public AiResult suggest(AiTaxonContext context, String model) {
    String apiKey = props.getApiKeys().get(Provider.ANTHROPIC);
    if (apiKey == null || apiKey.isBlank()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "no Anthropic API key configured");
    }
    AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
    MessageCreateParams params = MessageCreateParams.builder()
        .model(model)
        .maxTokens(MAX_TOKENS)
        .addUserMessage(AiPrompts.userPrompt(context))
        .build();

    Message response = client.messages().create(params);
    String json = response.content().stream()
        .flatMap(block -> block.text().stream())
        .map(TextBlock::text)
        .collect(Collectors.joining());

    AiSuggestions suggestions = AiJson.parse(objectMapper, json);
    return new AiResult(suggestions, (int) response.usage().inputTokens(),
        (int) response.usage().outputTokens());
  }
}
