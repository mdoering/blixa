package org.catalogueoflife.editor.name;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import life.catalogue.api.model.CslName;
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

    List<CslName> authors = r.author();
    assertThat(authors).hasSize(6);
    // must-have: no LaTeX grouping braces survive into any structured name field.
    for (CslName n : authors) {
      if (n.getFamily() != null) {
        assertThat(n.getFamily()).doesNotContain("{").doesNotContain("}");
      }
      if (n.getGiven() != null) {
        assertThat(n.getGiven()).doesNotContain("{").doesNotContain("}");
      }
      if (n.getLiteral() != null) {
        assertThat(n.getLiteral()).doesNotContain("{").doesNotContain("}");
      }
    }
    assertThat(authors.get(0).getFamily()).isEqualTo("Bánki");
    assertThat(authors.get(0).getGiven()).isEqualTo("Olaf");
    assertThat(authors.get(1).getFamily()).isEqualTo("Roskov");
    assertThat(authors.get(1).getGiven()).isEqualTo("Yury");
    assertThat(authors.get(2).getFamily()).isEqualTo("Döring");
    assertThat(authors.get(2).getGiven()).isEqualTo("Markus");
    assertThat(authors.get(3).getFamily()).isEqualTo("Ower");
    assertThat(authors.get(3).getGiven()).isEqualTo("Geoff");
    // comma-free entries (an institution, or a name with no given part left after the "and" split)
    // have no family/given -- stored as a single literal instead.
    assertThat(authors.get(4).getFamily()).isNull();
    assertThat(authors.get(4).getLiteral()).isEqualTo("Hernández Robles");
    assertThat(authors.get(5).getFamily()).isNull();
    assertThat(authors.get(5).getLiteral()).isEqualTo("World Flora Online");

    // other fields that flow through the same field() helper must also come out brace-free.
    assertThat(r.title()).isEqualTo("Catalogue of Life");
    assertThat(r.publisher()).isEqualTo("Catalogue of Life Foundation");
    assertThat(r.issn()).isEqualTo("2405-8858");
    assertThat(r.doi()).isEqualTo("10.48580/dgy8b");
    assertThat(r.link()).isEqualTo("https://www.checklistbank.org/dataset/315557");
    assertThat(r.issued()).isEqualTo("2026");
  }
}
