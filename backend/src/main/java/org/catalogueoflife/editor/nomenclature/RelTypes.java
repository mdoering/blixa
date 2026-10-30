package org.catalogueoflife.editor.nomenclature;

import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import life.catalogue.api.vocab.NomRelType;

/** Maps stored relation type strings (UI or raw ColDP spellings) to {@link NomRelType}. */
public final class RelTypes {

  /** Labels of {@link NomRelType#HOMOTYPIC_RELATIONS}, as the UI writes them. */
  public static final List<String> HOMOTYPIC_LABELS =
      NomRelType.HOMOTYPIC_RELATIONS.stream().map(RelTypes::label).sorted().toList();

  private RelTypes() {}

  /** Tolerant of case, surrounding whitespace and '_'/'-' separators; unknown or blank gives null. */
  @Nullable
  public static NomRelType parse(@Nullable String stored) {
    if (stored == null || stored.isBlank()) {
      return null;
    }
    try {
      return NomRelType.valueOf(
          stored.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_'));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  public static boolean isHomotypic(@Nullable String stored) {
    NomRelType t = parse(stored);
    return t != null && NomRelType.HOMOTYPIC_RELATIONS.contains(t);
  }

  /** Lowercase with spaces, e.g. "spelling correction". */
  public static String label(NomRelType t) {
    return t.name().toLowerCase(Locale.ROOT).replace('_', ' ');
  }
}
