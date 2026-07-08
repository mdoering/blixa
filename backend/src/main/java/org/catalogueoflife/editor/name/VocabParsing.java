package org.catalogueoflife.editor.name;

import java.util.Locale;
import life.catalogue.api.vocab.GeoTime;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

// Shared tolerant string -> vocabulary parsing for the name-core descriptive fields (status,
// nomStatus, gender, environment, temporalRangeStart/End, ...). Mirrors ProjectService's
// nomCode/license parsing (chunk 1): the frontend sends lowercase/hyphenated strings (e.g.
// "cc-by", "not established"), so normalize case/spaces/hyphens before matching the enum
// constant name, turning anything unrecognized into a 400 rather than letting an
// IllegalArgumentException surface as a 500.
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

  /**
   * Blank/null -> null; otherwise the named {@link GeoTime} (e.g. "Jurassic", "Holocene"), or a
   * 400. Unlike {@link #parse}, GeoTime isn't a plain enum, and {@link GeoTime#byName} silently
   * returns null for an unrecognized name instead of throwing, so the invalid case is checked
   * explicitly here rather than via IllegalArgumentException.
   */
  static GeoTime parseGeoTime(String raw, String fieldName) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    GeoTime geoTime = GeoTime.byName(raw.trim());
    if (geoTime == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid " + fieldName + ": " + raw);
    }
    return geoTime;
  }

  private static String normalize(String s) {
    return s.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
  }
}
