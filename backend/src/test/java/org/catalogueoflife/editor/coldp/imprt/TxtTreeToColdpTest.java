package org.catalogueoflife.editor.coldp.imprt;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.catalogueoflife.editor.parse.NameParserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TxtTreeToColdpTest {

  @Test
  void writesNameUsageAndMetadata(@TempDir Path dir) throws Exception {
    String tree = "Panthera [genus]\n  Panthera leo [species]\n    =Felis leo\n";
    int rows = new TxtTreeToColdp(new NameParserService()).convert(new StringReader(tree), dir, "My Tree");

    assertThat(rows).isEqualTo(3);
    assertThat(Files.exists(dir.resolve("metadata.yaml"))).isTrue();
    Path nu = dir.resolve("NameUsage.tsv");
    assertThat(Files.exists(nu)).isTrue();

    List<String> lines = Files.readAllLines(nu);
    String header = lines.get(0);
    assertThat(header).contains("ID").contains("parentID").contains("status").contains("scientificName");

    // genus row: accepted, no parent
    assertThat(lines).anyMatch(l -> l.contains("Panthera") && l.contains("accepted") && l.contains("genus"));
    // synonym row: status synonym, parentID = the leo row's ID
    assertThat(lines).anyMatch(l -> l.contains("Felis leo") && l.contains("synonym"));
    assertThat(Files.readString(dir.resolve("metadata.yaml"))).contains("My Tree");
  }

  @Test
  void splitsAuthorshipAndRelatesHomotypicSynonyms(@TempDir Path dir) throws Exception {
    String tree = "Panthera leo (Linnaeus, 1758) [species]\n  ≡Felis leo Linnaeus, 1758\n  =Leo leo\n";
    new TxtTreeToColdp(new NameParserService()).convert(new StringReader(tree), dir, null);

    List<String> usages = Files.readAllLines(dir.resolve("NameUsage.tsv"));
    assertThat(usages).anyMatch(l -> l.contains("\tPanthera leo\t(Linnaeus, 1758)\t"));
    assertThat(usages).anyMatch(l -> l.contains("\tFelis leo\tLinnaeus, 1758\t"));
    List<String> relations = Files.readAllLines(dir.resolve("NameRelation.tsv"));
    assertThat(relations).hasSize(2); // header + Felis leo only
    assertThat(relations.get(1)).contains("homotypic");
  }

  @Test
  void plainListBecomesRoots(@TempDir Path dir) throws Exception {
    int rows = new TxtTreeToColdp(new NameParserService()).convert(new StringReader("Aus bus\nBus cus\n"), dir, null);
    assertThat(rows).isEqualTo(2);
  }
}
