package org.catalogueoflife.editor.coldp.io;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import life.catalogue.api.model.VerbatimRecord;
import life.catalogue.coldp.ColdpTerm;
import life.catalogue.csv.ColdpReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves {@link ColdpTsv#writeFile} produces a {@code NameUsage.tsv} that {@code ColdpReader}
 * (the CLB reader library, used unmodified) reads back correctly: bare headers are recognized,
 * row/column structure survives a value containing a raw TAB and NEWLINE, and TabWriter's
 * backslash-escaping of those control characters round-trips through the univocity TSV parser's
 * matching escape handling (default TSV escape char {@code \}, {@code \t}/{@code \n}/{@code \r}).
 *
 * <p>Note: {@code CsvReader.clean()} (invoked while building each {@link VerbatimRecord})
 * subsequently collapses any run of whitespace -- including the tab/newline that the parser just
 * unescaped -- into a single literal space and trims. So the value that comes back out of {@code
 * VerbatimRecord.get(...)} is whitespace-normalized, not byte-identical to what was written; this
 * test asserts that normalized form, which is the real, correct end-to-end contract of the CLB
 * reader (not a defect of {@link ColdpTsv}).
 */
class ColdpTsvIT {

  @Test
  void roundTripsThroughColdpReader(@TempDir Path dir) throws IOException {
    Map<ColdpTerm, String> row1 = new LinkedHashMap<>();
    row1.put(ColdpTerm.ID, "1");
    row1.put(ColdpTerm.scientificName, "Abies alba");
    row1.put(ColdpTerm.rank, "species");
    row1.put(ColdpTerm.status, "accepted");

    Map<ColdpTerm, String> row2 = new LinkedHashMap<>();
    row2.put(ColdpTerm.ID, "2");
    // embeds a raw TAB and NEWLINE to prove TabWriter escaping <-> reader unescaping of row/column
    // structure (see class javadoc for why the round-tripped value is whitespace-normalized).
    row2.put(ColdpTerm.scientificName, "Abies\talba\nsubsp.\tescapeme");
    row2.put(ColdpTerm.rank, "subspecies");
    row2.put(ColdpTerm.status, "synonym");

    ColdpTsv.writeFile(dir, ColdpTerm.NameUsage, List.of(row1, row2));

    // ColdpReader is neither Closeable nor AutoCloseable -- no try-with-resources.
    ColdpReader reader = ColdpReader.from(dir);
    assertThat(reader.hasSchema(ColdpTerm.NameUsage)).isTrue();

    List<VerbatimRecord> recs = reader.stream(ColdpTerm.NameUsage).toList();
    assertThat(recs).hasSize(2);

    VerbatimRecord rec1 = recs.get(0);
    assertThat(rec1.get(ColdpTerm.ID)).isEqualTo("1");
    assertThat(rec1.get(ColdpTerm.scientificName)).isEqualTo("Abies alba");
    assertThat(rec1.get(ColdpTerm.rank)).isEqualTo("species");
    assertThat(rec1.get(ColdpTerm.status)).isEqualTo("accepted");

    VerbatimRecord rec2 = recs.get(1);
    assertThat(rec2.get(ColdpTerm.ID)).isEqualTo("2");
    // the embedded tab/newline (and the literal tab used as a plain separator) all collapse to a
    // single space each -- proof the escaped control chars were correctly unescaped in place
    // (not left as literal backslash-t/backslash-n, and not corrupting row/column boundaries).
    assertThat(rec2.get(ColdpTerm.scientificName)).isEqualTo("Abies alba subsp. escapeme");
    assertThat(rec2.get(ColdpTerm.rank)).isEqualTo("subspecies");
    assertThat(rec2.get(ColdpTerm.status)).isEqualTo("synonym");
  }
}
