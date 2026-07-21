package org.catalogueoflife.editor.parse;

import java.util.Locale;
import javax.annotation.Nullable;
import org.catalogueoflife.editor.name.NameUsage;
import org.gbif.nameparser.api.NameParser;
import org.gbif.nameparser.api.NomCode;
import org.gbif.nameparser.api.ParseResult;
import org.gbif.nameparser.api.Rank;
import org.gbif.nameparser.rust.NameParserRust;
import org.gbif.nameparser.util.NameFormatter;
import org.springframework.stereotype.Service;

/**
 * Wraps the GBIF name-parser 5.0.0 {@link NameParser} to atomize a {@link NameUsage}'s scientific
 * name + authorship, and to render formatted display names, per design-spec Appendix A.
 *
 * <p>At 5.0.0 the reference Java parser was dropped; the sole implementation is {@link
 * NameParserRust}, a binding over a Rust core reached through the JDK Foreign Function &amp; Memory
 * API (requires JDK 25). It is thread-safe and reusable, so a single instance is held for the
 * lifetime of this service.
 *
 * <p>Also at 5.0.0, {@link NameParser#parse} no longer throws {@code UnparsableNameException};
 * it returns a sealed {@link ParseResult} that is one of {@code Parsed} (a full {@link
 * org.gbif.nameparser.api.ParsedName}), {@code Informal} (a semi-structured name with no atomized
 * {@code ParsedName}), or {@code Unparsable} (not a name at all -- virus, formula, placeholder,
 * identifier, ...). Only {@code Parsed} carries atomized fields; the other two are treated here as
 * un-atomizable.
 */
@Service
public class NameParserService {

  private final NameParser parser = new NameParserRust();

  /**
   * Parses {@code u.getScientificName()} + {@code u.getAuthorship()} using the usage's own {@code
   * rank} and the project's nomenclatural code, and populates the atomized name fields, {@code
   * nameType} and {@code parseState} on {@code u} in place.
   *
   * <p>Never throws: when the parser yields no atomized {@link org.gbif.nameparser.api.ParsedName}
   * -- an {@code Unparsable} name (virus names, BOLD BINs, hybrid formulas, placeholders, ...) or a
   * semi-structured {@code Informal} name -- {@code u.parseState} is set to {@code "UNPARSABLE"} and
   * {@code u.nameType} to the result's {@link org.gbif.nameparser.api.NameType}; the caller always
   * gets back a usable, if unparsed, {@link NameUsage}.
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
    ParseResult result = parser.parse(u.getScientificName(), u.getAuthorship(), rank, nomCode);
    if (result.parsed().isPresent()) {
      ParsedNameMapping.applyTo(result.parsed().get(), u);
    } else {
      u.setParseState("UNPARSABLE");
      u.setNameType(result.type());
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
    ParseResult result = parser.parse(u.getScientificName(), u.getAuthorship(), rank, nomCode);
    return result.parsed()
        .map(pn -> html ? NameFormatter.canonicalCompleteHtml(pn) : NameFormatter.canonicalComplete(pn))
        .orElseGet(() -> rawName(u));
  }

  /** The raw {@code scientificName}, with authorship appended when present -- the unparsable fallback. */
  private static String rawName(NameUsage u) {
    String name = u.getScientificName();
    if (u.getAuthorship() != null && !u.getAuthorship().isBlank()) {
      return name + " " + u.getAuthorship();
    }
    return name;
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
