package org.catalogueoflife.editor.name.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.Reference;
import org.catalogueoflife.editor.name.Status;
import org.junit.jupiter.api.Test;

class SearchTsvTest {

  @Test
  void writesUsageHeaderAndRowsWithNullsAsEmpty() throws Exception {
    NameUsage full = new NameUsage();
    full.setId(5);
    full.setScientificName("Aus bus");
    full.setAuthorship("L.");
    full.setRank("species");
    full.setStatus(Status.ACCEPTED);

    NameUsage bare = new NameUsage();
    bare.setId(6);
    bare.setScientificName("Cus"); // authorship / rank / status left null

    var out = new ByteArrayOutputStream();
    SearchTsv.writeUsages(out, List.of(full, bare));
    String[] lines = out.toString(StandardCharsets.UTF_8).split("\\R");

    assertThat(lines[0].split("\t", -1))
        .containsExactly("id", "scientificName", "authorship", "rank", "status");
    assertThat(lines[1].split("\t", -1)).containsExactly("5", "Aus bus", "L.", "species", "ACCEPTED");
    assertThat(lines[2].split("\t", -1)).containsExactly("6", "Cus", "", "", "");
  }

  @Test
  void writesReferenceHeaderAndRows() throws Exception {
    Reference r = new Reference();
    r.setId(3);
    r.setCitation("Darwin 1859");
    r.setDoi("10.1/x");
    r.setIssued("1859");

    var out = new ByteArrayOutputStream();
    SearchTsv.writeReferences(out, List.of(r));
    String[] lines = out.toString(StandardCharsets.UTF_8).split("\\R");

    assertThat(lines[0].split("\t", -1)).containsExactly("id", "citation", "doi", "issued");
    assertThat(lines[1].split("\t", -1)).containsExactly("3", "Darwin 1859", "10.1/x", "1859");
  }
}
