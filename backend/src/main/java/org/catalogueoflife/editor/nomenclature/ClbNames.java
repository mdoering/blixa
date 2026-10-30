package org.catalogueoflife.editor.nomenclature;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import life.catalogue.api.model.Name;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.parse.NameParserService;
import org.gbif.nameparser.api.Authorship;
import org.gbif.nameparser.api.NomCode;

/**
 * Maps a Blixa {@link NameUsage} onto the CLB {@link Name} the nomenclature module works on. The
 * inverse of how {@code ParsedNameMapping} stores atomized authorship: authors are pipe-joined and
 * a bracketed year keeps its square brackets.
 */
public final class ClbNames {

  private ClbNames() {}

  public static Name toName(NameUsage u, @Nullable NomCode code) {
    Name n = new Name();
    n.setId(String.valueOf(u.getId()));
    n.setRank(NameParserService.parseRank(u.getRank()));
    n.setScientificName(u.getScientificName());
    n.setAuthorship(u.getAuthorship());
    n.setUninomial(u.getUninomial());
    n.setGenus(u.getGenus());
    n.setInfragenericEpithet(u.getInfragenericEpithet());
    n.setSpecificEpithet(u.getSpecificEpithet());
    n.setInfraspecificEpithet(u.getInfraspecificEpithet());
    n.setCultivarEpithet(u.getCultivarEpithet());
    if (u.getNotho() != null) {
      n.setNotho(u.getNotho());
    }
    n.setType(u.getNameType());
    n.setNomStatus(u.getNomStatus());
    n.setPublishedInYear(u.getPublishedInYear());
    n.setCode(code);
    n.setCombinationAuthorship(
        authorship(u.getCombinationAuthorship(), u.getCombinationExAuthorship(),
            u.getCombinationAuthorshipYear()));
    n.setBasionymAuthorship(
        authorship(u.getBasionymAuthorship(), u.getBasionymExAuthorship(),
            u.getBasionymAuthorshipYear()));
    return n;
  }

  /** Splits pipe-joined author strings and unbrackets a year; null/blank input gives an empty Authorship. */
  public static Authorship authorship(@Nullable String authors, @Nullable String exAuthors,
      @Nullable String year) {
    Authorship a = new Authorship();
    a.setAuthors(split(authors));
    a.setExAuthors(split(exAuthors));
    if (year != null && !year.isBlank()) {
      String y = year.trim();
      if (y.startsWith("[") && y.endsWith("]")) {
        a.setBracketedYear(true);
        y = y.substring(1, y.length() - 1).trim();
      }
      a.setYear(y.isEmpty() ? null : y);
    }
    return a;
  }

  private static List<String> split(@Nullable String joined) {
    List<String> out = new ArrayList<>();
    if (joined != null) {
      for (String s : joined.split("\\|")) {
        if (!s.isBlank()) {
          out.add(s.trim());
        }
      }
    }
    return out;
  }
}
