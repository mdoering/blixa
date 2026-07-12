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
    // Reference.tsv still wants the "; "-joined free-text form, exactly CLB's own
    // CslName.toColdpString(CslName[]) produces (and ImportRunService.loadReferences' RefMapping.
    // parseNames reads back on reimport). Task 4 (reference-model-overhaul plan) may revisit this
    // to carry the structured form losslessly instead.
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

  private static String toColdpNameString(List<CslName> names) {
    return (names == null || names.isEmpty()) ? null : CslName.toColdpString(names.toArray(new CslName[0]));
  }
}
