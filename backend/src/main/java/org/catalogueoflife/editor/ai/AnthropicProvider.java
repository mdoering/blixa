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
        .addUserMessage(prompt(context))
        .build();

    Message response = client.messages().create(params);
    String json = response.content().stream()
        .flatMap(block -> block.text().stream())
        .map(TextBlock::text)
        .collect(Collectors.joining());

    AiSuggestions suggestions = parse(json);
    return new AiResult(suggestions, (int) response.usage().inputTokens(),
        (int) response.usage().outputTokens());
  }

  private AiSuggestions parse(String raw) {
    // The model is asked for JSON only, but tolerate stray prose / markdown fences by slicing to the
    // outermost object.
    int start = raw.indexOf('{');
    int end = raw.lastIndexOf('}');
    if (start < 0 || end <= start) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI did not return JSON");
    }
    try {
      return objectMapper.readValue(raw.substring(start, end + 1), AiSuggestions.class);
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI returned invalid JSON");
    }
  }

  private static String prompt(AiTaxonContext c) {
    String existing = c.existingSynonyms() == null || c.existingSynonyms().isEmpty()
        ? "(none)" : String.join("; ", c.existingSynonyms());
    return """
        You are a taxonomic data assistant helping curate a Catalogue of Life checklist. For the focal
        taxon below, suggest supplementary data. Respond with ONLY a single JSON object (no prose, no
        markdown fences) matching exactly this shape:
        {
          "synonyms": [{"scientificName": "", "authorship": "", "nomStatus": null, "referenceDoi": null}],
          "vernacularNames": [{"name": "", "language": "ISO 639 code"}],
          "distributions": [{"area": ""}],
          "descriptions": ["short factual description"],
          "references": [{"doi": "", "citation": ""}],
          "etymology": null
        }
        Rules:
        - Synonyms are the most important output: other scientific names that are synonyms of this
          accepted taxon, with authorship, and the DOI of the nomenclatural reference when you know it.
        - Give a DOI (not a URL) for references when possible; omit any reference you are unsure exists.
        - Do NOT repeat any of the existing synonyms listed below.
        - Use empty arrays / null where you have nothing. Return valid JSON only.

        Focal taxon:
        - scientificName: %s
        - authorship: %s
        - rank: %s
        - nomenclatural code: %s
        - existing synonyms (do not repeat): %s
        """
        .formatted(nz(c.scientificName()), nz(c.authorship()), nz(c.rank()), nz(c.nomCode()), existing);
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }
}
