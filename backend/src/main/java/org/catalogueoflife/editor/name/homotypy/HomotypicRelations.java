package org.catalogueoflife.editor.name.homotypy;

import java.util.Set;

// The ColDP NomRelType values that denote homotypy (objective/nomenclatural synonymy), in the
// canonical lowercase UI form. Stored name_relation.type is not canonical (import writes the raw
// ColDP cell), so normalize() lowercases and maps _/- to spaces before any comparison.
public final class HomotypicRelations {
  private HomotypicRelations() {}

  public static final Set<String> TYPES = Set.of(
      "basionym", "homotypic", "spelling correction", "based on", "replacement name", "superfluous");

  public static String normalize(String type) {
    return type == null ? "" : type.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[_-]", " ");
  }

  public static boolean isHomotypic(String type) {
    return TYPES.contains(normalize(type));
  }
}
