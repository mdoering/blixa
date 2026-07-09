package org.catalogueoflife.editor.name;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.catalogueoflife.editor.name.dto.CreateReferenceRequest;
import org.jbibtex.BibTeXDatabase;
import org.jbibtex.BibTeXEntry;
import org.jbibtex.BibTeXParser;
import org.jbibtex.Key;
import org.jbibtex.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

// Maps external reference formats (a Crossref/CSL work message; BibTeX entries) into the editor's
// CreateReferenceRequest so they flow through the normal ReferenceService.create path. Pure/static;
// the HTTP fetch lives in CrossrefClient and the persistence in ReferenceImportService.
public final class RefMapping {

  private RefMapping() {}

  // --- Crossref / CSL ---

  public static CreateReferenceRequest fromCrossref(JsonNode message) {
    String title = text(message.path("title").path(0));
    String author = crossrefNames(message.path("author"));
    String editor = crossrefNames(message.path("editor"));
    String container = text(message.path("container-title").path(0));
    String year = crossrefYear(message.path("issued"));
    String volume = text(message.path("volume"));
    String issue = text(message.path("issue"));
    String page = text(message.path("page"));
    String publisher = text(message.path("publisher"));
    String doi = text(message.path("DOI"));
    String isbn = text(message.path("ISBN").path(0));
    String issn = text(message.path("ISSN").path(0));
    String link = text(message.path("URL"));
    String type = text(message.path("type"));
    String citation = citation(author, year, title, container, volume, issue, page);
    return new CreateReferenceRequest(citation, type, author, editor, title, container, year, volume,
        issue, page, publisher, doi, isbn, issn, link, null);
  }

  private static String crossrefNames(JsonNode arr) {
    if (arr == null || !arr.isArray() || arr.isEmpty()) {
      return null;
    }
    List<String> names = new ArrayList<>();
    for (JsonNode a : arr) {
      String family = text(a.path("family"));
      String given = text(a.path("given"));
      if (family != null && given != null) {
        names.add(family + ", " + given);
      } else if (family != null) {
        names.add(family);
      } else {
        String literal = text(a.path("name"));
        if (literal != null) {
          names.add(literal);
        }
      }
    }
    return names.isEmpty() ? null : String.join("; ", names);
  }

  private static String crossrefYear(JsonNode issued) {
    JsonNode y = issued.path("date-parts").path(0).path(0);
    return (y.isMissingNode() || y.isNull()) ? null : String.valueOf(y.asInt());
  }

  private static String text(JsonNode n) {
    if (n == null || n.isMissingNode() || n.isNull()) {
      return null;
    }
    String s = n.asString();
    return (s == null || s.isBlank()) ? null : s.trim();
  }

  // --- BibTeX ---

  public static List<CreateReferenceRequest> fromBibtex(String bibtex) {
    if (bibtex == null || bibtex.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no BibTeX provided");
    }
    BibTeXDatabase db;
    try {
      db = new BibTeXParser().parse(new StringReader(bibtex));
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "could not parse BibTeX");
    }
    List<CreateReferenceRequest> out = new ArrayList<>();
    for (BibTeXEntry e : db.getEntries().values()) {
      String author = names(field(e, "author"));
      String editor = names(field(e, "editor"));
      String title = field(e, "title");
      String container = field(e, "journal") != null ? field(e, "journal") : field(e, "booktitle");
      String year = field(e, "year");
      String volume = field(e, "volume");
      String issue = field(e, "number");
      String page = field(e, "pages");
      String type = e.getType() == null ? null : e.getType().getValue();
      String citation = citation(author, year, title, container, volume, issue, page);
      out.add(new CreateReferenceRequest(citation, type, author, editor, title, container, year,
          volume, issue, page, field(e, "publisher"), field(e, "doi"), field(e, "isbn"),
          field(e, "issn"), field(e, "url"), null));
    }
    if (out.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no BibTeX entries found");
    }
    return out;
  }

  private static String field(BibTeXEntry e, String key) {
    Value v = e.getField(new Key(key));
    if (v == null) {
      return null;
    }
    String s = v.toUserString();
    if (s == null) {
      return null;
    }
    s = s.replaceAll("\\s+", " ").trim();
    return s.isEmpty() ? null : s;
  }

  // BibTeX author/editor lists are " and "-separated; the editor stores a single "; "-joined string.
  private static String names(String raw) {
    if (raw == null) {
      return null;
    }
    return String.join("; ", raw.split("(?i)\\s+and\\s+"));
  }

  // --- shared ---

  private static String citation(String author, String year, String title, String container,
      String volume, String issue, String page) {
    StringBuilder sb = new StringBuilder();
    if (author != null) {
      sb.append(author).append(' ');
    }
    if (year != null) {
      sb.append('(').append(year).append("). ");
    }
    if (title != null) {
      sb.append(title).append(". ");
    }
    if (container != null) {
      sb.append(container);
      if (volume != null) {
        sb.append(' ').append(volume);
      }
      if (issue != null) {
        sb.append('(').append(issue).append(')');
      }
      if (page != null) {
        sb.append(": ").append(page);
      }
    }
    String s = sb.toString().trim();
    return s.isEmpty() ? null : s;
  }
}
