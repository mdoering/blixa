package org.catalogueoflife.editor.coldp.imprt;

import com.google.common.base.Splitter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.catalogueoflife.editor.name.Status;

// Reverse-vocab helpers for parsing a ColDP archive's TSV string values back into our typed model:
// the exact inverse of NameUsageColdpWriter's vocab transforms (coldpStatus/lower/join/joinInts).
// Kept as pure, Spring-free static helpers (unlike ExportRun's infra, which is all Spring beans)
// since these have no I/O or state -- ColdpParseTest exercises them directly with no Spring context.
public final class ColdpParse {
  private ColdpParse() {}
  private static final Splitter CSV = Splitter.on(',').trimResults().omitEmptyStrings();

  public static Status parseStatus(String s) {          // inverse of NameUsageColdpWriter.coldpStatus
    if (s == null || s.isBlank()) return null;
    return switch (s.trim().toLowerCase(Locale.ROOT)) {
      case "accepted" -> Status.ACCEPTED;
      case "synonym", "ambiguous synonym" -> Status.SYNONYM;
      case "misapplied" -> Status.MISAPPLIED;
      case "provisionally accepted", "unassessed" -> Status.UNASSESSED;
      default -> Status.UNASSESSED;                     // unknown -> UNASSESSED (safest non-accepted)
    };
  }
  public static <E extends Enum<E>> E parseEnum(Class<E> type, String s) {  // inverse of lower(e)
    if (s == null || s.isBlank()) return null;
    try { return Enum.valueOf(type, s.trim().toUpperCase(Locale.ROOT).replace(' ', '_')); }
    catch (IllegalArgumentException e) { return null; }  // unknown vocab -> null (dropped), not fatal
  }
  public static List<String> csv(String s) { return s == null ? List.of() : CSV.splitToList(s); }
  public static List<Integer> csvInts(String s) {
    List<Integer> out = new ArrayList<>();
    for (String p : csv(s)) { try { out.add(Integer.valueOf(p)); } catch (NumberFormatException ignore) {} }
    return out;
  }
  public static Integer intOrNull(String s) {
    if (s == null || s.isBlank()) return null;
    try { return Integer.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
  }
  // Task 5's TypeMaterial.latitude/longitude: same null/blank/unparseable -> null contract as
  // intOrNull, just for Double (Distribution's own area/areaID stay free-text -- only TypeMaterial's
  // coordinates are numeric; see ChildColdpWriter.typeMaterialRow).
  public static Double doubleOrNull(String s) {
    if (s == null || s.isBlank()) return null;
    try { return Double.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
  }
}
