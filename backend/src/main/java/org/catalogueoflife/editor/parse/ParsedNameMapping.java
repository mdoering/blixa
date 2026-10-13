package org.catalogueoflife.editor.parse;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nullable;
import org.catalogueoflife.editor.name.NameUsage;
import org.gbif.nameparser.api.Authorship;
import org.gbif.nameparser.api.NamePart;
import org.gbif.nameparser.api.NomCode;
import org.gbif.nameparser.api.ParsedName;
import org.gbif.nameparser.util.NameFormatter;

/**
 * Maps a GBIF name-parser {@link ParsedName} onto the atomized nomenclatural fields of a {@link
 * NameUsage}, per design-spec Appendix A.
 *
 * <p>Only the <em>name-level</em> {@link ParsedName#getCombinationAuthorship()} /
 * {@link ParsedName#getBasionymAuthorship()} are mapped. The per-epithet {@code
 * getGenericAuthorship()} / {@code getSpecificAuthorship()} are deliberately ignored — they
 * attach authors to a non-terminal epithet and are not part of a proper name for our purposes.
 *
 * <p>The author and year columns have no room for an {@link Authorship#isAnonymous() anonymous}
 * work or a {@link Authorship#isBracketedYear() bracketed year}, so -- like CLB's ColDP {@code
 * AuthorAtoms} (CatalogueOfLife/backend#1611) -- both travel inside the values: an anonymous work
 * as the code specific "anon."/"Anon." or its attributed authors each in square brackets, a
 * bracketed year as "[1916]".
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
    u.setNotho(firstNotho(pn.getNotho()));

    if (pn.getRank() != null) {
      u.setRank(pn.getRank().name().toLowerCase(Locale.ROOT));
    }
    u.setNameType(pn.getType());
    u.setParseState(pn.getState() == null ? null : pn.getState().name());

    Authorship combination = pn.getCombinationAuthorship();
    if (combination != null) {
      u.setCombinationAuthorship(join(authors(combination, pn.getCode())));
      u.setCombinationExAuthorship(join(combination.getExAuthors()));
      u.setCombinationAuthorshipYear(year(combination));
      u.setSanctioningAuthor(combination.getSanctioningAuthor());
    }

    // A sanctioned basionym's own sanctioning author has no column and is not kept.
    Authorship basionym = pn.getBasionymAuthorship();
    if (basionym != null) {
      u.setBasionymAuthorship(join(authors(basionym, pn.getCode())));
      u.setBasionymExAuthorship(join(basionym.getExAuthors()));
      u.setBasionymAuthorshipYear(year(basionym));
    }
  }

  /** The authors of {@code a}, marking an anonymous work as described in the class javadoc. */
  private static List<String> authors(Authorship a, @Nullable NomCode code) {
    if (!a.isAnonymous()) {
      return a.getAuthors();
    }
    if (a.hasAuthors()) {
      return a.getAuthors().stream().map(x -> "[" + x + "]").toList();
    }
    return List.of(NameFormatter.anonymousAuthor(code));
  }

  /** The year of {@code a}, in square brackets if it is a bracketed year. */
  private static String year(Authorship a) {
    return a.isBracketedYear() && a.getYear() != null ? "[" + a.getYear() + "]" : a.getYear();
  }

  /** Pipe-joins an author list into the single authoritative string column, or null if empty. */
  private static String join(List<String> authors) {
    if (authors == null || authors.isEmpty()) {
      return null;
    }
    return String.join("|", authors);
  }

  // The parser (and CLB's Name) model notho as a Set<NamePart> (a name can in principle carry a
  // hybrid marker at more than one part), but name_usage.notho is a single scalar NamePart column
  // -- take the highest-level one (the set iterates in its natural GENERIC..INFRASPECIFIC order) as
  // the representative value, or null if the name isn't a hybrid at all. Shared with ClbUsageMapper,
  // which reduces the same Set when copying a CLB Name onto a NameUsage.
  public static NamePart firstNotho(Set<NamePart> notho) {
    return (notho == null || notho.isEmpty()) ? null : notho.iterator().next();
  }
}
