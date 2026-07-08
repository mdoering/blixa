package org.catalogueoflife.editor.name;

import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

// Shared tolerant string -> vocabulary parsing for the name-core descriptive fields (status,
// nomStatus, gender, environment, ...). Mirrors ProjectService's nomCode/license parsing
// (chunk 1): the frontend sends lowercase/hyphenated strings (e.g. "cc-by", "not established"),
// so normalize case/spaces/hyphens before matching the enum constant name, turning anything
// unrecognized into a 400 rather than letting an IllegalArgumentException surface as a 500.
// temporalRangeStart/End are plain free-text Strings for now (not parsed here) -- vocab-backed
// validation against the CLB vocab API is a future concern.
final class VocabParsing {

  private VocabParsing() {}

  /** Blank/null -> null; otherwise the matching constant of {@code type}, or a 400. */
  static <E extends Enum<E>> E parse(Class<E> type, String raw, String fieldName) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Enum.valueOf(type, normalize(raw));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid " + fieldName + ": " + raw);
    }
  }

  /** Like {@link #parse}, but blank/null is itself a 400 -- for required fields (e.g. status). */
  static <E extends Enum<E>> E requireParse(Class<E> type, String raw, String fieldName) {
    E value = parse(type, raw, fieldName);
    if (value == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
    }
    return value;
  }

  private static String normalize(String s) {
    return s.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
  }
}
