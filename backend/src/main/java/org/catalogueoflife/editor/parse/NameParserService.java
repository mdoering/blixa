package org.catalogueoflife.editor.parse;

import java.util.Locale;
import javax.annotation.Nullable;
import org.catalogueoflife.editor.name.NameUsage;
import org.gbif.nameparser.NameParserImpl;
import org.gbif.nameparser.api.NameParser;
import org.gbif.nameparser.api.NomCode;
import org.gbif.nameparser.api.ParsedName;
import org.gbif.nameparser.api.Rank;
import org.gbif.nameparser.api.UnparsableNameException;
import org.gbif.nameparser.util.NameFormatter;
import org.springframework.stereotype.Service;

/**
 * Wraps the GBIF name-parser 4.2.0 {@link NameParser} to atomize a {@link NameUsage}'s scientific
 * name + authorship, and to render formatted display names, per design-spec Appendix A.
 *
 * <p>{@link NameParserImpl} is thread-safe and reusable, so a single instance is held for the
 * lifetime of this service.
 */
@Service
public class NameParserService {

  private final NameParser parser = new NameParserImpl();

  /**
   * Parses {@code u.getScientificName()} + {@code u.getAuthorship()} using the usage's own {@code
   * rank} and the project's nomenclatural code, and populates the atomized name fields, {@code
   * nameType} and {@code parseState} on {@code u} in place.
   *
   * <p>Never throws: on {@link UnparsableNameException} (virus names, BOLD BINs, hybrid formulas,
   * placeholders, ...) {@code u.parseState} is set to {@code "UNPARSABLE"} and {@code u.nameType}
   * to the exception's {@link org.gbif.nameparser.api.NameType}; the caller always gets back a
   * usable, if unparsed, {@link NameUsage}.
   *
   * @param nomCode the project's nomenclatural code, or {@code null} if unknown
   */
  public void parseInto(NameUsage u, @Nullable NomCode nomCode) {
    // Always clear the previously-atomized fields first: on the success path
    // ParsedNameMapping.applyTo re-populates whatever the new parse yields (so a name that no
    // longer has, say, a basionym authorship doesn't keep a stale one from a prior parse), and on
    // the failure path below they must reflect the now-unparsable name, i.e. all null.
    clearParsedFields(u);
    Rank rank = parseRank(u.getRank());
    try {
      ParsedName pn = parser.parse(u.getScientificName(), u.getAuthorship(), rank, nomCode);
      ParsedNameMapping.applyTo(pn, u);
    } catch (UnparsableNameException e) {
      u.setParseState("UNPARSABLE");
      u.setNameType(e.getType() == null ? null : e.getType().name());
    }
  }

  /**
   * Nulls out all atomized name-part and authorship fields derived from a previous parse.
   * {@code scientificName}, {@code authorship} and {@code rank} are the authoritative inputs and
   * are deliberately left untouched.
   */
  private static void clearParsedFields(NameUsage u) {
    u.setUninomial(null);
    u.setGenus(null);
    u.setInfragenericEpithet(null);
    u.setSpecificEpithet(null);
    u.setInfraspecificEpithet(null);
    u.setCultivarEpithet(null);
    u.setNotho(null);
    u.setCombinationAuthorship(null);
    u.setCombinationExAuthorship(null);
    u.setCombinationAuthorshipYear(null);
    u.setBasionymAuthorship(null);
    u.setBasionymExAuthorship(null);
    u.setBasionymAuthorshipYear(null);
    u.setSanctioningAuthor(null);
  }

  /**
   * Renders a formatted display name for {@code u} via {@link NameFormatter}'s {@code
   * canonicalComplete}/{@code canonicalCompleteHtml}. Falls back to the raw {@code
   * scientificName} (plus authorship, if present) when the name cannot be parsed.
   */
  public String formatName(NameUsage u, @Nullable NomCode nomCode, boolean html) {
    Rank rank = parseRank(u.getRank());
    try {
      ParsedName pn = parser.parse(u.getScientificName(), u.getAuthorship(), rank, nomCode);
      return html ? NameFormatter.canonicalCompleteHtml(pn) : NameFormatter.canonicalComplete(pn);
    } catch (UnparsableNameException e) {
      String name = u.getScientificName();
      if (u.getAuthorship() != null && !u.getAuthorship().isBlank()) {
        return name + " " + u.getAuthorship();
      }
      return name;
    }
  }

  /** Tolerant string -> Rank, falling back to {@link Rank#UNRANKED} rather than throwing. */
  private static Rank parseRank(@Nullable String rank) {
    if (rank == null || rank.isBlank()) {
      return Rank.UNRANKED;
    }
    try {
      return Rank.valueOf(normalize(rank));
    } catch (IllegalArgumentException e) {
      return Rank.UNRANKED;
    }
  }

  private static String normalize(String s) {
    return s.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
  }
}
