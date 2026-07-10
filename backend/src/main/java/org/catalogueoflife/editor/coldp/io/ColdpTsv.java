package org.catalogueoflife.editor.coldp.io;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import life.catalogue.coldp.ColdpTerm;
import life.catalogue.common.io.TabWriter;

/**
 * Term-keyed TSV write primitive for ColDP data files.
 *
 * <p>Writes one file per ColDP class/file term ({@code ColdpTerm.RESOURCES.get(fileTerm)} is the
 * canonical, ordered column list for that term), with a <b>bare</b> (unprefixed) header row —
 * e.g. {@code scientificName}, not {@code col:scientificName} — for maximum interop; {@code
 * life.catalogue.csv.ColdpReader} accepts both bare and {@code col:}-prefixed headers. Uses
 * {@link TabWriter} so tabs/newlines/carriage-returns embedded in values are escaped and the file
 * stays well-formed TSV.
 *
 * <p>Reading is done directly with {@code ColdpReader.from(Path)} from the CLB {@code reader}
 * library — no wrapper needed here.
 */
public final class ColdpTsv {

  private ColdpTsv() {}

  /**
   * Writes {@code dir/{fileTerm.simpleName()}.tsv}.
   *
   * @param dir the folder to write into; must already exist
   * @param fileTerm the ColDP class/file term, e.g. {@link ColdpTerm#NameUsage}
   * @param rows the rows to write, each a term-keyed map of column values in insertion-order
   *     iteration of {@code rows}; a row missing a column (or holding an explicit {@code null}
   *     value for it) writes an empty string for that column
   */
  public static void writeFile(
      Path dir, ColdpTerm fileTerm, Iterable<Map<ColdpTerm, String>> rows) throws IOException {
    List<ColdpTerm> cols = ColdpTerm.RESOURCES.get(fileTerm);
    Path file = dir.resolve(fileTerm.simpleName() + ".tsv");
    try (TabWriter writer = TabWriter.fromFile(file.toFile())) {
      writer.write(cols.stream().map(ColdpTerm::simpleName).toArray(String[]::new));
      for (Map<ColdpTerm, String> row : rows) {
        writer.write(cols.stream().map(t -> valueOrEmpty(row, t)).toArray(String[]::new));
      }
    }
  }

  private static String valueOrEmpty(Map<ColdpTerm, String> row, ColdpTerm term) {
    String value = row.getOrDefault(term, "");
    return value == null ? "" : value;
  }
}
