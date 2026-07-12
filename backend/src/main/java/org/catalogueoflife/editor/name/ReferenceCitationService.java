package org.catalogueoflife.editor.name;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import life.catalogue.api.model.CSLType;
import life.catalogue.api.model.CslData;
import life.catalogue.api.model.CslDate;
import life.catalogue.api.model.CslName;
import life.catalogue.common.csl.CslFormatter;
import life.catalogue.common.csl.CslFormatter.FORMAT;
import life.catalogue.common.csl.CslFormatter.STYLE;
import org.springframework.stereotype.Service;

// Task 3 of the reference-model-overhaul plan (docs/superpowers/plans/2026-07-12-reference-model-
// csl.md): generates a reference's `citation` from its structured fields via CLB's citeproc-backed
// CslFormatter, in a project-selectable style (Project.cslStyle, see V25__project_csl_style.sql),
// instead of trusting a caller-supplied string. ReferenceService.create/update call render() unless
// the reference is citationManual; ProjectService.updateMetadata calls it again for every non-manual
// reference when a project's cslStyle changes.
@Service
public class ReferenceCitationService {

  // CslFormatter#cite is `synchronized` in its entirety, and constructing a CslFormatter loads +
  // parses a CSL style file off the classpath (slow) -- one instance per STYLE, built lazily
  // (most projects only ever touch one style) and reused for the life of the app rather than
  // rebuilt on every render() call.
  private final Map<STYLE, CslFormatter> formatters = new ConcurrentHashMap<>();

  private static final Pattern YEAR = Pattern.compile("\\d{4}");

  // Renders `ref`'s structured fields into a plain-text citation in `cslStyle` (case-insensitive,
  // e.g. "apa"/"harvard"; null/unrecognized -> APA). Never throws: citeproc itself already
  // catches+logs rendering failures inside CslFormatter.cite (returning null), and this additionally
  // guards CslData assembly -- a reference save must never 500 just because its citation could not
  // be generated. Fallback order when citeproc produces nothing: `ref.getCitation()` (whatever is
  // already stored -- e.g. an imported citation-only string) if non-blank, THEN the minimal
  // "author (year) title" string. This ordering is deliberate, not incidental: render() must never
  // turn a non-empty citation into "" -- ProjectService.regenerateCitations persists whatever this
  // method returns straight into the citation column with no guard of its own beyond isStructured,
  // so a blank return here would silently blank out that caller's existing citation for real.
  public String render(Reference ref, String cslStyle) {
    try {
      CslData data = toCslData(ref);
      CslFormatter formatter = formatters.computeIfAbsent(resolveStyle(cslStyle),
          style -> new CslFormatter(style, FORMAT.TEXT));
      String citation = formatter.cite(data);
      if (citation != null && !citation.isBlank()) {
        return citation;
      }
    } catch (RuntimeException e) {
      // fall through to the fallback below
    }
    if (notBlank(ref.getCitation())) {
      return ref.getCitation();
    }
    return fallback(ref);
  }

  // Whether `ref` has enough structured content for render() to produce a real citation from --
  // used by ReferenceService.create/update to decide whether a caller-supplied `citation` string
  // should instead be (re)generated. A reference with none of these is presumed to carry only a
  // hand-typed/imported citation string with nothing to derive it from.
  public boolean isStructured(Reference ref) {
    return notEmpty(ref.getAuthor()) || notBlank(ref.getTitle()) || notBlank(ref.getContainerTitle())
        || notBlank(ref.getType());
  }

  // Package-private (not private) so ReferenceCitationIT can build the exact same CslData the
  // service used and assert equality against CLB's own CslFormatter output directly, rather than
  // duplicating this mapping in the test.
  static CslData toCslData(Reference ref) {
    CslData d = new CslData();
    // Same helper Task 2's ReferenceService.validateType uses to resolve the canonical wire string
    // back to the enum -- ref.getType() is already that canonical form by the time it's persisted.
    d.setType(CSLType.fromString(ref.getType()));
    d.setAuthor(toArray(ref.getAuthor()));
    d.setEditor(toArray(ref.getEditor()));
    d.setTitle(ref.getTitle());
    d.setContainerTitle(ref.getContainerTitle());
    d.setContainerTitleShort(ref.getContainerTitleShort());
    if (notBlank(ref.getIssued())) {
      // Literal form: verified against citeproc directly (APA/Harvard render a literal issued date
      // identically to a parsed year/month/day one) -- no need to parse the leading year into real
      // date-parts just to satisfy citeproc's renderer.
      d.setIssued(new CslDate(ref.getIssued()));
    }
    d.setVolume(ref.getVolume());
    d.setIssue(ref.getIssue());
    d.setPage(ref.getPage());
    d.setPublisher(ref.getPublisher());
    d.setDOI(ref.getDoi());
    d.setISBN(ref.getIsbn());
    d.setISSN(ref.getIssn());
    return d;
  }

  private static CslName[] toArray(List<CslName> names) {
    return notEmpty(names) ? names.toArray(new CslName[0]) : null;
  }

  // "apa"/"APA"/" Apa " all resolve to STYLE.APA; null/blank/unrecognized default to APA too --
  // matches the project.csl_style DB default (V25) and ProjectService.updateMetadata's own
  // validation, so an unrecognized value should never actually reach here in practice, but render()
  // still must not throw over one.
  static STYLE resolveStyle(String cslStyle) {
    if (notBlank(cslStyle)) {
      try {
        return STYLE.valueOf(cslStyle.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ignored) {
        // fall through to the default below
      }
    }
    return STYLE.APA;
  }

  // Last-resort citation, only reached when BOTH citeproc produced nothing (an unexpected rendering
  // failure, or a CslData citeproc itself considers "empty") AND ref.getCitation() was already blank
  // (render()'s own fallback order tries that first) -- non-blank as long as `ref` has *something*
  // structured, which is guaranteed by isStructured gating every call to render() (see
  // ReferenceService.applyCitation and ProjectService.regenerateCitations).
  private static String fallback(Reference ref) {
    StringBuilder sb = new StringBuilder();
    String author = firstAuthorName(ref);
    if (author != null) {
      sb.append(author);
    }
    String year = extractYear(ref.getIssued());
    if (year != null) {
      if (sb.length() > 0) sb.append(' ');
      sb.append('(').append(year).append(')');
    }
    if (notBlank(ref.getTitle())) {
      if (sb.length() > 0) sb.append(' ');
      sb.append(ref.getTitle());
    }
    return sb.toString();
  }

  private static String firstAuthorName(Reference ref) {
    if (notEmpty(ref.getAuthor())) {
      CslName a = ref.getAuthor().get(0);
      if (notBlank(a.getFamily())) return a.getFamily();
      if (notBlank(a.getLiteral())) return a.getLiteral();
    }
    return null;
  }

  private static String extractYear(String issued) {
    if (issued == null) return null;
    Matcher m = YEAR.matcher(issued);
    return m.find() ? m.group() : null;
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }

  private static boolean notEmpty(List<?> l) {
    return l != null && !l.isEmpty();
  }
}
