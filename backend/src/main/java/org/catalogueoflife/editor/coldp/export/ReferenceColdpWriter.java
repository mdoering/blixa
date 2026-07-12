package org.catalogueoflife.editor.coldp.export;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import life.catalogue.api.model.CslName;
import life.catalogue.coldp.ColdpTerm;
import org.catalogueoflife.editor.coldp.io.ColdpTsv;
import org.catalogueoflife.editor.name.Reference;
import org.catalogueoflife.editor.name.ReferenceMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Builds Reference.tsv: one row per reference, in ReferenceMapper.findAllByProject's ID order.
// Always writes the file, even for a project with zero references (mirrors NameUsageColdpWriter,
// which always writes NameUsage.tsv regardless of row count) -- an empty Reference.tsv with just a
// header row is valid ColDP and keeps the archive shape predictable across every export.
@Component
public class ReferenceColdpWriter {

  private final ReferenceMapper references;
  // coldp.pdf.base-url -- same key ReferenceController/PdfController use to build pdfUrl, so an
  // exported archive's `link` (when synthesized from a hosted PDF, see row() below) resolves to the
  // very same URL the app itself would have served.
  private final String pdfBaseUrl;

  public ReferenceColdpWriter(ReferenceMapper references, @Value("${coldp.pdf.base-url}") String pdfBaseUrl) {
    this.references = references;
    this.pdfBaseUrl = pdfBaseUrl;
  }

  /** Writes {@code dir/Reference.tsv} and returns the number of rows written. */
  public int write(Path dir, int projectId) throws IOException {
    List<Map<ColdpTerm, String>> rows =
        references.findAllByProject(projectId).stream().map(this::row).toList();
    ColdpTsv.writeFile(dir, ColdpTerm.Reference, rows);
    return rows.size();
  }

  private Map<ColdpTerm, String> row(Reference r) {
    Map<ColdpTerm, String> row = new LinkedHashMap<>();
    row.put(ColdpTerm.ID, String.valueOf(r.getId()));
    row.put(ColdpTerm.alternativeID, join(r.getAlternativeId()));
    row.put(ColdpTerm.citation, r.getCitation());
    row.put(ColdpTerm.type, r.getType());
    // author/editor are now structured CslName lists (V24__reference_csl.sql) -- ColDP's own
    // Reference.tsv still wants the "; "-joined free-text form that RefMapping.parseNames reads back
    // on reimport. We build that string ourselves below (toColdpNameString) rather than delegating to
    // CLB's own CslName.toColdpString(CslName[]), whose row format we otherwise replicate exactly --
    // toColdpString appends getFamily() unconditionally, so a literal-only CslName (family == null,
    // exactly what RefMapping.parseNames produces for an institution or comma-free name, e.g. "World
    // Flora Online") makes it write the 4 literal characters "null" into the TSV cell. Task 4
    // (reference-model-overhaul plan) may revisit this to carry the structured form losslessly
    // instead.
    row.put(ColdpTerm.author, toColdpNameString(r.getAuthor()));
    row.put(ColdpTerm.editor, toColdpNameString(r.getEditor()));
    row.put(ColdpTerm.title, r.getTitle());
    row.put(ColdpTerm.containerTitle, r.getContainerTitle());
    row.put(ColdpTerm.issued, r.getIssued());
    row.put(ColdpTerm.volume, r.getVolume());
    row.put(ColdpTerm.issue, r.getIssue());
    row.put(ColdpTerm.page, r.getPage());
    row.put(ColdpTerm.publisher, r.getPublisher());
    row.put(ColdpTerm.doi, r.getDoi());
    row.put(ColdpTerm.isbn, r.getIsbn());
    row.put(ColdpTerm.issn, r.getIssn());
    row.put(ColdpTerm.link, effectiveLink(r));
    row.put(ColdpTerm.accessed, r.getAccessed());
    row.put(ColdpTerm.remarks, r.getRemarks());
    return row;
  }

  // A hosted PDF is only ever used to FILL a blank link, never to override one the user set
  // explicitly -- see PdfService/ReferenceService.attachPdf's javadoc: `link` and `pdf` are
  // deliberately independent columns, and this is the one place they're reconciled into ColDP's
  // single `link` term.
  private String effectiveLink(Reference r) {
    if (r.getPdf() != null && (r.getLink() == null || r.getLink().isBlank())) {
      return pdfBaseUrl + "/" + r.getPdf();
    }
    return r.getLink();
  }

  private static String join(List<String> values) {
    return (values == null || values.isEmpty()) ? null : String.join(",", values);
  }

  // Mirrors CLB's CslName.toColdpString(CslName[]) row format -- "[non-dropping-particle ]family
  // [,given]" per name, joined with "; " (the separator RefMapping.parseNames splits on) -- but
  // built by hand instead of delegated, because that CLB helper appends getFamily() unconditionally
  // and therefore writes the string "null" for a literal-only name (see class-level comment above
  // this method's caller). A name with neither a literal nor a family/given is skipped entirely
  // rather than emitting an empty token.
  private static String toColdpNameString(List<CslName> names) {
    if (names == null || names.isEmpty()) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    for (CslName n : names) {
      String token = coldpToken(n);
      if (token == null || token.isBlank()) {
        continue;
      }
      if (sb.length() > 0) {
        sb.append("; ");
      }
      sb.append(token);
    }
    return sb.isEmpty() ? null : sb.toString();
  }

  private static String coldpToken(CslName n) {
    if (n == null) {
      return null;
    }
    if (n.getLiteral() != null && !n.getLiteral().isBlank()) {
      return n.getLiteral();
    }
    StringBuilder sb = new StringBuilder();
    if (n.getNonDroppingParticle() != null && !n.getNonDroppingParticle().isBlank()) {
      sb.append(n.getNonDroppingParticle()).append(' ');
    }
    if (n.getFamily() != null && !n.getFamily().isBlank()) {
      sb.append(n.getFamily());
    }
    if (n.getGiven() != null && !n.getGiven().isBlank()) {
      if (sb.length() > 0) {
        sb.append(',');
      }
      sb.append(n.getGiven());
    }
    return sb.isEmpty() ? null : sb.toString();
  }
}
