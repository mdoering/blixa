package org.catalogueoflife.editor.ai;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

// Parses an LLM's textual reply into AiSuggestions. Tolerates stray prose / markdown fences by
// slicing to the outermost { ... } before deserializing. Shared by every provider adapter.
final class AiJson {

  private AiJson() {}

  static AiSuggestions parse(ObjectMapper mapper, String raw) {
    int start = raw == null ? -1 : raw.indexOf('{');
    int end = raw == null ? -1 : raw.lastIndexOf('}');
    if (start < 0 || end <= start) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI did not return JSON");
    }
    try {
      return mapper.readValue(raw.substring(start, end + 1), AiSuggestions.class);
    } catch (RuntimeException invalid) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI returned invalid JSON");
    }
  }
}
