package org.catalogueoflife.editor.name.export;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import life.catalogue.common.io.TabWriter;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.Reference;

/**
 * Streams flat search results (name usages, references) as TSV with a bare header row, matching the
 * columns of the on-screen search tables. Reuses the CLB {@link TabWriter} so tabs/newlines embedded
 * in a value are escaped and the file stays well-formed TSV (same primitive {@code ColdpTsv} uses).
 */
public final class SearchTsv {

  private SearchTsv() {}

  public static void writeUsages(OutputStream out, List<NameUsage> rows) throws IOException {
    try (TabWriter w = TabWriter.fromStream(out)) {
      w.write(new String[] {"id", "scientificName", "authorship", "rank", "status"});
      for (NameUsage u : rows) {
        w.write(new String[] {
            nz(u.getId()),
            nz(u.getScientificName()),
            nz(u.getAuthorship()),
            nz(u.getRank()),
            u.getStatus() == null ? "" : u.getStatus().name(),
        });
      }
    }
  }

  public static void writeReferences(OutputStream out, List<Reference> rows) throws IOException {
    try (TabWriter w = TabWriter.fromStream(out)) {
      w.write(new String[] {"id", "citation", "doi", "issued"});
      for (Reference r : rows) {
        w.write(new String[] {nz(r.getId()), nz(r.getCitation()), nz(r.getDoi()), nz(r.getIssued())});
      }
    }
  }

  private static String nz(Object v) {
    return v == null ? "" : v.toString();
  }
}
