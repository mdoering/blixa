package org.catalogueoflife.editor.parse;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.catalogueoflife.editor.name.NameUsage;
import org.gbif.nameparser.api.Authorship;
import org.gbif.nameparser.api.NamePart;
import org.gbif.nameparser.api.ParsedName;

/**
 * Maps a GBIF name-parser {@link ParsedName} onto the atomized nomenclatural fields of a {@link
 * NameUsage}, per design-spec Appendix A.
 *
 * <p>Only the <em>name-level</em> {@link ParsedName#getCombinationAuthorship()} /
 * {@link ParsedName#getBasionymAuthorship()} are mapped. The per-epithet {@code
 * getGenericAuthorship()} / {@code getSpecificAuthorship()} are deliberately ignored — they
 * attach authors to a non-terminal epithet and are not part of a proper name for our purposes.
 */
public final class ParsedNameMapping {

  private ParsedNameMapping() {}

  /** Applies the atomized parts, name-level authorship, rank, nameType and notho of {@code pn} onto {@code u}. */
  public static void applyTo(ParsedName pn, NameUsage u) {
    u.setUninomial(pn.getUninomial());
    u.setGenus(pn.getGenus());
    u.setInfragenericEpithet(pn.getInfragenericEpithet());
    u.setSpecificEpithet(pn.getSpecificEpithet());
    u.setInfraspecificEpithet(pn.getInfraspecificEpithet());
    u.setCultivarEpithet(pn.getCultivarEpithet());
    u.setNotho(joinNotho(pn.getNotho()));

    if (pn.getRank() != null) {
      u.setRank(pn.getRank().name().toLowerCase(Locale.ROOT));
    }
    u.setNameType(pn.getType() == null ? null : pn.getType().name());
    u.setParseState(pn.getState() == null ? null : pn.getState().name());

    Authorship combination = pn.getCombinationAuthorship();
    if (combination != null) {
      u.setCombinationAuthorship(join(combination.getAuthors()));
      u.setCombinationExAuthorship(join(combination.getExAuthors()));
      u.setCombinationAuthorshipYear(combination.getYear());
    }

    Authorship basionym = pn.getBasionymAuthorship();
    if (basionym != null) {
      u.setBasionymAuthorship(join(basionym.getAuthors()));
      u.setBasionymExAuthorship(join(basionym.getExAuthors()));
      u.setBasionymAuthorshipYear(basionym.getYear());
    }

    u.setSanctioningAuthor(pn.getSanctioningAuthor());
  }

  /** Pipe-joins an author list into the single authoritative string column, or null if empty. */
  private static String join(List<String> authors) {
    if (authors == null || authors.isEmpty()) {
      return null;
    }
    return String.join("|", authors);
  }

  /** Space-joins the notho name parts (in their natural GENERIC..INFRASPECIFIC order) into the single notho column. */
  private static String joinNotho(Set<NamePart> notho) {
    if (notho == null || notho.isEmpty()) {
      return null;
    }
    return notho.stream().map(NamePart::name).collect(Collectors.joining(" "));
  }
}
