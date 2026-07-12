package org.catalogueoflife.editor.name;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.catalogueoflife.editor.name.dto.CreateReferenceRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Pure unit tests for the external-format mappers (no Spring/DB).
class RefMappingTest {

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  @Test
  void mapsBibtexEntry() {
    String bibtex =
        """
        @article{key1,
          author = {Doe, Jane and Smith, John},
          title = {A great paper},
          journal = {Journal of Things},
          year = {2020},
          volume = {12},
          number = {3},
          pages = {45--67},
          doi = {10.1/xyz}
        }
        """;
    List<CreateReferenceRequest> refs = RefMapping.fromBibtex(bibtex);
    assertThat(refs).hasSize(1);
    CreateReferenceRequest r = refs.get(0);
    assertThat(r.author()).hasSize(2);
    assertThat(r.author().get(0).getFamily()).isEqualTo("Doe");
    assertThat(r.author().get(0).getGiven()).isEqualTo("Jane");
    assertThat(r.author().get(1).getFamily()).isEqualTo("Smith");
    assertThat(r.author().get(1).getGiven()).isEqualTo("John");
    assertThat(r.title()).isEqualTo("A great paper");
    assertThat(r.containerTitle()).isEqualTo("Journal of Things");
    assertThat(r.issued()).isEqualTo("2020");
    assertThat(r.volume()).isEqualTo("12");
    assertThat(r.issue()).isEqualTo("3");
    assertThat(r.doi()).isEqualTo("10.1/xyz");
    // BibTeX's "article" entry type maps to CSL's "article-journal" (RefMapping.bibtexType) --
    // NOT the raw BibTeX string, which is not a valid CSLType wire value and would 400 the create.
    assertThat(r.type()).isEqualTo("article-journal");
    assertThat(r.citation()).contains("A great paper");
  }

  @Test
  void mapsBibtexUrldateToAccessed() {
    String bibtex =
        """
        @misc{key2,
          title = {A dataset},
          url = {https://example.org/data},
          urldate = {2026-07-10}
        }
        """;
    List<CreateReferenceRequest> refs = RefMapping.fromBibtex(bibtex);
    assertThat(refs).hasSize(1);
    assertThat(refs.get(0).accessed()).isEqualTo("2026-07-10");
  }

  @Test
  void mapsCrossrefMessage() {
    String json =
        """
        {
          "title": ["Systema Naturae"],
          "author": [{"family": "Linnaeus", "given": "Carl"}],
          "container-title": ["Nature"],
          "issued": {"date-parts": [[1758, 1, 1]]},
          "volume": "1",
          "issue": "2",
          "page": "1-824",
          "publisher": "Salvius",
          "DOI": "10.5/abc",
          "ISSN": ["1234-5678"],
          "URL": "https://doi.org/10.5/abc",
          "type": "book"
        }
        """;
    JsonNode message = JSON.readTree(json);
    CreateReferenceRequest r = RefMapping.fromCrossref(message);
    assertThat(r.title()).isEqualTo("Systema Naturae");
    assertThat(r.author()).hasSize(1);
    assertThat(r.author().get(0).getFamily()).isEqualTo("Linnaeus");
    assertThat(r.author().get(0).getGiven()).isEqualTo("Carl");
    assertThat(r.containerTitle()).isEqualTo("Nature");
    assertThat(r.issued()).isEqualTo("1758");
    assertThat(r.doi()).isEqualTo("10.5/abc");
    assertThat(r.issn()).isEqualTo("1234-5678");
    assertThat(r.link()).isEqualTo("https://doi.org/10.5/abc");
    assertThat(r.type()).isEqualTo("book");
    assertThat(r.citation()).contains("Linnaeus").contains("Systema Naturae");
  }

  @Test
  void mapsCrossrefAccessedDateParts() {
    String json =
        """
        {
          "title": ["A dataset"],
          "accessed": {"date-parts": [[2026, 7, 10]]}
        }
        """;
    JsonNode message = JSON.readTree(json);
    CreateReferenceRequest r = RefMapping.fromCrossref(message);
    assertThat(r.accessed()).isEqualTo("2026-07-10");
  }

  @Test
  void mapsCrossrefAccessedYearOnly() {
    String json =
        """
        {
          "title": ["A dataset"],
          "accessed": {"date-parts": [[2026]]}
        }
        """;
    JsonNode message = JSON.readTree(json);
    CreateReferenceRequest r = RefMapping.fromCrossref(message);
    assertThat(r.accessed()).isEqualTo("2026");
  }

  // Task 4 (reference-model-overhaul plan): Crossref's own type vocabulary ("journal-article") is
  // NOT a CSL wire value ("article-journal") -- passing it straight through used to 400 the moment
  // ReferenceService.create started validating `type` against CSLType. Confirms fromCrossref's
  // parsed request already carries the canonicalized value, not the raw Crossref string.
  @Test
  void mapsCrossrefJournalArticleTypeToCanonicalArticleJournal() {
    String json =
        """
        {
          "title": ["A Paper"],
          "type": "journal-article"
        }
        """;
    JsonNode message = JSON.readTree(json);
    CreateReferenceRequest r = RefMapping.fromCrossref(message);
    assertThat(r.type()).isEqualTo("article-journal");
  }

  // Same for BibTeX: "@incollection" is not a CSL wire value either.
  @Test
  void mapsBibtexIncollectionTypeToCanonicalChapter() {
    String bibtex =
        """
        @incollection{key3,
          title = {A Chapter},
          booktitle = {A Book}
        }
        """;
    List<CreateReferenceRequest> refs = RefMapping.fromBibtex(bibtex);
    assertThat(refs.get(0).type()).isEqualTo("chapter");
  }

  @Test
  void typeMappingFunctionsCoverCommonSourceTypesAndUnknownsMapToNull() {
    assertThat(RefMapping.crossrefType("journal-article")).isEqualTo("article-journal");
    assertThat(RefMapping.crossrefType("book-chapter")).isEqualTo("chapter");
    assertThat(RefMapping.crossrefType("proceedings-article")).isEqualTo("paper-conference");
    assertThat(RefMapping.crossrefType("posted-content")).isEqualTo("article");
    assertThat(RefMapping.crossrefType("not-a-real-crossref-type")).isNull();
    assertThat(RefMapping.crossrefType(null)).isNull();

    assertThat(RefMapping.bibtexType("article")).isEqualTo("article-journal");
    assertThat(RefMapping.bibtexType("incollection")).isEqualTo("chapter");
    assertThat(RefMapping.bibtexType("inbook")).isEqualTo("chapter");
    assertThat(RefMapping.bibtexType("inproceedings")).isEqualTo("paper-conference");
    assertThat(RefMapping.bibtexType("phdthesis")).isEqualTo("thesis");
    assertThat(RefMapping.bibtexType("techreport")).isEqualTo("report");
    assertThat(RefMapping.bibtexType("misc")).isNull();
    assertThat(RefMapping.bibtexType("unpublished")).isNull();
    assertThat(RefMapping.bibtexType(null)).isNull();

    assertThat(RefMapping.dataciteType("dataset")).isEqualTo("dataset");
    assertThat(RefMapping.dataciteType("JournalArticle")).isEqualTo("article-journal");
    assertThat(RefMapping.dataciteType("BookChapter")).isEqualTo("chapter");
    assertThat(RefMapping.dataciteType("not-a-real-datacite-type")).isNull();
    assertThat(RefMapping.dataciteType(null)).isNull();
  }
}
