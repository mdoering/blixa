package org.catalogueoflife.editor.coldp.export;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import life.catalogue.coldp.ColdpTerm;
import org.catalogueoflife.editor.coldp.io.ColdpTsv;
import org.catalogueoflife.editor.name.Reference;
import org.catalogueoflife.editor.name.ReferenceMapper;
import org.springframework.stereotype.Component;

// Builds Reference.tsv: one row per reference, in ReferenceMapper.findAllByProject's ID order.
// Always writes the file, even for a project with zero references (mirrors NameUsageColdpWriter,
// which always writes NameUsage.tsv regardless of row count) -- an empty Reference.tsv with just a
// header row is valid ColDP and keeps the archive shape predictable across every export.
@Component
public class ReferenceColdpWriter {

  private final ReferenceMapper references;

  public ReferenceColdpWriter(ReferenceMapper references) {
    this.references = references;
  }

  /** Writes {@code dir/Reference.tsv} and returns the number of rows written. */
  public int write(Path dir, int projectId) throws IOException {
    List<Map<ColdpTerm, String>> rows =
        references.findAllByProject(projectId).stream().map(ReferenceColdpWriter::row).toList();
    ColdpTsv.writeFile(dir, ColdpTerm.Reference, rows);
    return rows.size();
  }

  private static Map<ColdpTerm, String> row(Reference r) {
    Map<ColdpTerm, String> row = new LinkedHashMap<>();
    row.put(ColdpTerm.ID, String.valueOf(r.getId()));
    row.put(ColdpTerm.alternativeID, join(r.getAlternativeId()));
    row.put(ColdpTerm.citation, r.getCitation());
    row.put(ColdpTerm.type, r.getType());
    row.put(ColdpTerm.author, r.getAuthor());
    row.put(ColdpTerm.editor, r.getEditor());
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
    row.put(ColdpTerm.link, r.getLink());
    row.put(ColdpTerm.accessed, r.getAccessed());
    row.put(ColdpTerm.remarks, r.getRemarks());
    return row;
  }

  private static String join(List<String> values) {
    return (values == null || values.isEmpty()) ? null : String.join(",", values);
  }
}
