package org.catalogueoflife.editor.name;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.catalogueoflife.editor.name.dto.CreateReferenceRequest;
import org.junit.jupiter.api.Test;

// Pure unit test for RefMapping.fromBibtex (no Spring/DB) -- reproduces a bug report where
// author names came back with literal LaTeX grouping braces still attached, e.g.
// "{Bánki}, {Olaf}; {Roskov}, {Yury}; ...", because jbibtex's Value#toUserString() only strips
// the outer field delimiter, not inner "{...}" grouping used to protect capitalization.
class RefMappingBibtexTest {

  private static final String BIBTEX =
      """
      @misc{315557,
      \tpublisher = {Catalogue of Life Foundation},
      \taddress = {Amsterdam, Netherlands},
      \tversion = {2026-06-19 XR},
      \tissn = {2405-8858},
      \turl = {https://www.checklistbank.org/dataset/315557},
      \tdoi = {10.48580/dgy8b},
      \ttitle = {Catalogue of Life},
      \tauthor = {{Bánki}, {Olaf} and {Roskov}, {Yury} and {Döring}, {Markus} and {Ower}, {Geoff} and {Hernández Robles} and {World Flora Online}},
      \tyear = 2026,
      \tmonth = 6
      }
      """;

  @Test
  void stripsBracesFromDoubleBracketedAuthors() {
    List<CreateReferenceRequest> refs = RefMapping.fromBibtex(BIBTEX);
    assertThat(refs).hasSize(1);
    CreateReferenceRequest r = refs.get(0);

    // must-have: no LaTeX grouping braces survive into the stored author string.
    assertThat(r.author()).doesNotContain("{").doesNotContain("}");

    String[] names = r.author().split("; ");
    assertThat(names).hasSize(6);
    assertThat(names[0]).isEqualTo("Bánki, Olaf");
    assertThat(r.author())
        .isEqualTo("Bánki, Olaf; Roskov, Yury; Döring, Markus; Ower, Geoff; Hernández Robles; World Flora Online");

    // other fields that flow through the same field() helper must also come out brace-free.
    assertThat(r.title()).isEqualTo("Catalogue of Life");
    assertThat(r.publisher()).isEqualTo("Catalogue of Life Foundation");
    assertThat(r.issn()).isEqualTo("2405-8858");
    assertThat(r.doi()).isEqualTo("10.48580/dgy8b");
    assertThat(r.link()).isEqualTo("https://www.checklistbank.org/dataset/315557");
    assertThat(r.issued()).isEqualTo("2026");
  }
}
