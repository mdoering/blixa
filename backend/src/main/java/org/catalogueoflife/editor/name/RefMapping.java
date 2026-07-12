package org.catalogueoflife.editor.name;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.catalogueoflife.editor.name.dto.CreateReferenceRequest;
import org.jbibtex.BibTeXDatabase;
import org.jbibtex.BibTeXEntry;
import org.jbibtex.BibTeXParser;
import org.jbibtex.Key;
import org.jbibtex.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

// Maps external reference formats (a Crossref/CSL work message; a DataCite JSON:API attributes
// node; BibTeX entries) into the editor's CreateReferenceRequest so they flow through the normal
// ReferenceService.create path. Pure/static; the HTTP fetches live in CrossrefClient/DataciteClient
// and the persistence in ReferenceImportService.
public final class RefMapping {

  private RefMapping() {}

  // --- DOI normalization ---

  // A raw DOI input may arrive bare (10.xxxx/yyyy), "doi:"-prefixed (case-insensitive), or as a
  // resolver URL (https://doi.org/..., http://doi.org/..., https://dx.doi.org/...). Strip whichever
  // of those wraps the bare DOI and trim, so CrossrefClient/DataciteClient always see 10.xxxx/yyyy.
  private static final Pattern DOI_PREFIX =
      Pattern.compile("^\\s*(?:doi:|https?://(?:dx\\.)?doi\\.org/)?\\s*", Pattern.CASE_INSENSITIVE);

  public static String normalizeDoi(String raw) {
    if (raw == null) {
      return null;
    }
    return DOI_PREFIX.matcher(raw).replaceFirst("").trim();
  }

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
    String accessed = crossrefDate(message.path("accessed"));
    String citation = citation(author, year, title, container, volume, issue, page);
    return new CreateReferenceRequest(citation, type, author, editor, title, container, year, volume,
        issue, page, publisher, doi, isbn, issn, link, accessed, null);
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

  // CSL date field (e.g. "accessed") -> an ISO string, as precise as the date-parts triple allows:
  // [[2026,7,10]] -> "2026-07-10", [[2026,7]] -> "2026-07", [[2026]] -> "2026". Zero-pads month/day.
  private static String crossrefDate(JsonNode dateField) {
    JsonNode parts = dateField.path("date-parts").path(0);
    JsonNode y = parts.path(0);
    if (y.isMissingNode() || y.isNull()) {
      return null;
    }
    JsonNode m = parts.path(1);
    if (m.isMissingNode() || m.isNull()) {
      return String.valueOf(y.asInt());
    }
    JsonNode d = parts.path(2);
    if (d.isMissingNode() || d.isNull()) {
      return "%04d-%02d".formatted(y.asInt(), m.asInt());
    }
    return "%04d-%02d-%02d".formatted(y.asInt(), m.asInt(), d.asInt());
  }

  private static String text(JsonNode n) {
    if (n == null || n.isMissingNode() || n.isNull()) {
      return null;
    }
    String s = n.asString();
    return (s == null || s.isBlank()) ? null : s.trim();
  }

  // --- DataCite ---

  // Maps the `data.attributes` node of DataCite's GET /dois/{doi} response (JSON:API). Used as a
  // fallback when a DOI isn't registered with Crossref (datasets, software, and other DataCite-only
  // DOIs commonly aren't).
  public static CreateReferenceRequest fromDatacite(JsonNode attributes) {
    String title = text(attributes.path("titles").path(0).path("title"));
    String author = dataciteNames(attributes.path("creators"), null);
    String editor = dataciteNames(attributes.path("contributors"), "Editor");
    String year = dataciteYear(attributes.path("publicationYear"));
    String publisher = dataciteText(attributes.path("publisher"));
    JsonNode container = attributes.path("container");
    String containerTitle = text(container.path("title"));
    String volume = text(container.path("volume"));
    String issue = text(container.path("issue"));
    String page = joinPage(text(container.path("firstPage")), text(container.path("lastPage")));
    String doi = text(attributes.path("doi"));
    String link = text(attributes.path("url"));
    String type = dataciteText(attributes.path("types").path("resourceTypeGeneral"));
    if (type != null) {
      type = type.toLowerCase();
    }
    String citation = citation(author, year, title, containerTitle, volume, issue, page);
    return new CreateReferenceRequest(citation, type, author, editor, title, containerTitle, year,
        volume, issue, page, publisher, doi, null, null, link, null, null);
  }

  // DataCite creators/contributors: each entry prefers the already-formatted "name" (typically
  // "Family, Given"), falling back to familyName/givenName when name is absent. When
  // contributorType is non-null, only entries whose contributorType matches are included (e.g.
  // "Editor" for the editor field) -- creators (the author field) pass null to take everyone.
  private static String dataciteNames(JsonNode arr, String contributorType) {
    if (arr == null || !arr.isArray() || arr.isEmpty()) {
      return null;
    }
    List<String> names = new ArrayList<>();
    for (JsonNode a : arr) {
      if (contributorType != null
          && !contributorType.equalsIgnoreCase(text(a.path("contributorType")))) {
        continue;
      }
      String name = text(a.path("name"));
      if (name == null) {
        String family = text(a.path("familyName"));
        String given = text(a.path("givenName"));
        name = (family != null && given != null) ? family + ", " + given : family;
      }
      if (name != null) {
        names.add(name);
      }
    }
    return names.isEmpty() ? null : String.join("; ", names);
  }

  // publicationYear is a JSON number in the DataCite API, not a string.
  private static String dataciteYear(JsonNode n) {
    if (n == null || n.isMissingNode() || n.isNull()) {
      return null;
    }
    return n.isNumber() ? String.valueOf(n.asInt()) : text(n);
  }

  // publisher is usually a plain string, but defensively accept an object with a "name" child too
  // (newer DataCite schema versions allow a structured publisher).
  private static String dataciteText(JsonNode n) {
    if (n == null || n.isMissingNode() || n.isNull()) {
      return null;
    }
    return n.isObject() ? text(n.path("name")) : text(n);
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
          field(e, "issn"), field(e, "url"), field(e, "urldate"), null));
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
    // jbibtex's toUserString() only strips the outer field delimiter; LaTeX grouping braces used
    // to protect capitalization (e.g. "{{Bánki}, {Olaf} and ...}") survive into the value. Strip
    // them here so they don't leak into stored author/title/etc.
    s = s.replace("{", "").replace("}", "").trim();
    return s.isEmpty() ? null : s;
  }

  // BibTeX author/editor lists are " and "-separated; the editor stores a single "; "-joined string.
  private static String names(String raw) {
    if (raw == null) {
      return null;
    }
    return String.join("; ", raw.split("(?i)\\s+and\\s+"));
  }

  // --- RIS (Zotero/EndNote/Mendeley export) ---

  // A tagged RIS line: a 2-char tag, two spaces, a hyphen, a space, then the (possibly empty) value.
  private static final Pattern RIS_LINE = Pattern.compile("^([A-Z0-9]{2})  - (.*)$");

  public static List<CreateReferenceRequest> fromRis(String ris) {
    if (ris == null || ris.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no RIS provided");
    }
    List<CreateReferenceRequest> out = new ArrayList<>();
    // (tag, value) pairs in the order they appear in the record -- kept as a flat ordered list
    // (rather than grouped per tag) so that e.g. a record mixing AU and A1 lines still joins its
    // authors in document order rather than all-AU-then-all-A1.
    List<String[]> record = null;
    for (String line : ris.split("\r\n|\r|\n")) {
      if (line.isBlank()) {
        continue;
      }
      Matcher m = RIS_LINE.matcher(line);
      if (!m.matches()) {
        continue; // stray/unrecognized line outside the TY..ER shape: ignore
      }
      String tag = m.group(1);
      String value = m.group(2).trim();
      if (tag.equals("TY")) {
        record = new ArrayList<>();
        record.add(new String[] {tag, value});
      } else if (tag.equals("ER")) {
        if (record != null) {
          out.add(risRecordToRequest(record));
          record = null;
        }
      } else if (record != null) {
        record.add(new String[] {tag, value});
      } // a tag line before the first TY (no open record yet): ignore
    }
    if (out.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no RIS entries found");
    }
    return out;
  }

  private static CreateReferenceRequest risRecordToRequest(List<String[]> tags) {
    String type = risType(risFirst(tags, "TY"));
    String author = risJoin(tags, "AU", "A1");
    String editor = risJoin(tags, "A2", "ED");
    String title = risFirst(tags, "TI", "T1");
    String container = risFirst(tags, "T2", "JO", "JF", "JA");
    String year = risYear(risFirst(tags, "PY", "Y1"));
    String volume = risFirst(tags, "VL");
    String issue = risFirst(tags, "IS");
    String page = joinPage(risFirst(tags, "SP"), risFirst(tags, "EP"));
    String doi = risFirst(tags, "DO");
    String sn = risFirst(tags, "SN");
    String isbn = isIsbn(sn) ? sn : null;
    String issn = (sn != null && !isIsbn(sn)) ? sn : null;
    String publisher = risFirst(tags, "PB");
    String link = risFirst(tags, "UR", "L1");
    // CreateReferenceRequest has no dedicated alternative-id slot (see NameUsage/Author's
    // alternativeId for that concept elsewhere) and ReferenceService.create doesn't accept one
    // either, so the RIS record id -- if present -- rides along in the one free-text field left:
    // remarks, same as fromBibtex leaves remarks null when there's nothing to say.
    String id = risFirst(tags, "ID");
    String remarks = id == null ? null : "ris:" + id;
    String citation = citation(author, year, title, container, volume, issue, page);
    return new CreateReferenceRequest(citation, type, author, editor, title, container, year,
        volume, issue, page, publisher, doi, isbn, issn, link, null, remarks);
  }

  private static String risType(String risCode) {
    if (risCode == null) {
      return "document";
    }
    return switch (risCode) {
      case "JOUR" -> "article-journal";
      case "BOOK" -> "book";
      case "CHAP" -> "chapter";
      case "CONF", "CPAPER" -> "paper-conference";
      case "THES" -> "thesis";
      case "RPRT" -> "report";
      case "ELEC", "WEB" -> "webpage";
      default -> "document";
    };
  }

  // PY is plain "2020"; Y1 can be "2020/01/15/" (RIS's slash-delimited y/m/d/other-info date) --
  // either way, take the leading year.
  private static String risYear(String py) {
    if (py == null) {
      return null;
    }
    int slash = py.indexOf('/');
    String year = slash >= 0 ? py.substring(0, slash) : py;
    return year.isBlank() ? null : year;
  }

  // Joins a first/last page pair as "first-last" (or just "first" when there's no last). Shared by
  // RIS (SP/EP tags) and DataCite (container.firstPage/lastPage).
  private static String joinPage(String first, String last) {
    if (first == null) {
      return null;
    }
    return last == null ? first : first + "-" + last;
  }

  // SN is ambiguous in RIS (both books and journals use it): treat it as an ISBN when it looks
  // like one (10 or 13 digits, optional hyphens/spaces, ISBN-10 may end in X), otherwise ISSN.
  private static boolean isIsbn(String sn) {
    if (sn == null) {
      return false;
    }
    String stripped = sn.replaceAll("[-\\s]", "");
    if (stripped.length() == 10) {
      return stripped.substring(0, 9).matches("\\d+") && stripped.substring(9).matches("[\\dXx]");
    }
    return stripped.length() == 13 && stripped.matches("\\d{13}");
  }

  // First non-blank value among the given tag names, scanning the record in document order.
  private static String risFirst(List<String[]> tags, String... tagNames) {
    for (String[] pair : tags) {
      if (contains(tagNames, pair[0]) && !pair[1].isBlank()) {
        return pair[1];
      }
    }
    return null;
  }

  // All values among the given tag names (e.g. AU and A1 both feed "author"), in document order,
  // "; "-joined.
  private static String risJoin(List<String[]> tags, String... tagNames) {
    List<String> values = new ArrayList<>();
    for (String[] pair : tags) {
      if (contains(tagNames, pair[0])) {
        values.add(pair[1]);
      }
    }
    return values.isEmpty() ? null : String.join("; ", values);
  }

  private static boolean contains(String[] tagNames, String tag) {
    for (String t : tagNames) {
      if (t.equals(tag)) {
        return true;
      }
    }
    return false;
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
