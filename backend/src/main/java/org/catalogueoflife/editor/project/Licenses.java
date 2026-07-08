package org.catalogueoflife.editor.project;

import java.util.Locale;
import life.catalogue.api.vocab.License;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

// COL permits only two licenses. On the wire (UI + ColDP) we use the standard SPDX/ColDP identifiers
// "CC0-1.0" and "CC-BY-4.0"; internally we keep the restricted life.catalogue.api.vocab.License enum,
// whose constant names are the shorter CC0 / CC_BY -- hence the explicit mapping here rather than a
// naive valueOf() (which is what rejected "CC0-1.0" before).
public final class Licenses {

  private Licenses() {}

  /** The vocab enum -> the canonical wire identifier (null-safe). */
  public static String toWire(License license) {
    if (license == null) {
      return null;
    }
    return switch (license) {
      case CC0 -> "CC0-1.0";
      case CC_BY -> "CC-BY-4.0";
      // Only CC0/CC_BY are ever stored (parse() rejects the rest), so this is unreachable for
      // persisted values; fall back to the name for safety.
      default -> license.name();
    };
  }

  /**
   * Tolerantly parse a wire license string (and common variants, e.g. "cc0", "CC-BY-4.0") into one
   * of the two permitted licenses. Blank/null -> null (license is optional). Anything else -> 400.
   */
  public static License parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    return switch (normalized) {
      case "CC0", "CC01", "CC010" -> License.CC0;
      case "CCBY", "CCBY4", "CCBY40" -> License.CC_BY;
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "license must be CC0-1.0 or CC-BY-4.0");
    };
  }
}
