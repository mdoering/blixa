package org.catalogueoflife.editor.coldp.imprt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import life.catalogue.coldp.ColdpTerm;
import org.catalogueoflife.editor.coldp.io.ColdpMetadata;
import org.catalogueoflife.editor.coldp.io.ColdpMetadata.ColdpMetadataDto;
import org.catalogueoflife.editor.coldp.io.ColdpTsv;
import org.catalogueoflife.editor.coldp.io.ColdpZip;
import org.catalogueoflife.editor.name.Author;
import org.catalogueoflife.editor.name.AuthorMapper;
import org.catalogueoflife.editor.name.Reference;
import org.catalogueoflife.editor.name.ReferenceMapper;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Task 3's end-to-end proof: the import job (ImportRunService.loadTransactional, via
// self.run -> loadReferences/loadAuthors) loads Reference.tsv + Author.tsv into the freshly created
// project, inverting ReferenceColdpWriter/AuthorColdpWriter field-for-field, and -- since this run
// asks for preserveIds=true, idScope="src" -- appends a "src:<archive-row-ID>" CURIE to every
// created row's alternativeId on top of whatever alternativeID CURIE the archive itself carried.
// Mirrors ImportApiIT's multipart POST + poll-until-DONE pattern; the interesting assertions here
// are the reference/author field mapping and the two-CURIE alternativeId, not the project-creation
// side effect ImportApiIT already covers.
@AutoConfigureMockMvc
class ImportRefAuthorIT extends AbstractPostgresIT {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;
  @Autowired ReferenceMapper references;
  @Autowired AuthorMapper authors;

  private void ensureUser(String username) {
    AppUser existing = users.requireByUsernameOrNull(username);
    if (existing == null) users.createLocal(username, "pw", username);
  }

  private JsonNode getRun(long runId) throws Exception {
    String body = mvc.perform(get("/api/projects/import/" + runId))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body);
  }

  // Same bounded-poll discipline as ImportApiIT.pollUntilTerminal: poll the real GET endpoint until
  // the async run leaves RUNNING, failing loudly with the last-seen row rather than hanging.
  private JsonNode pollUntilTerminal(long runId) throws Exception {
    Instant deadline = Instant.now().plus(TIMEOUT);
    JsonNode last;
    do {
      last = getRun(runId);
      if (!"RUNNING".equals(last.get("status").asString())) {
        return last;
      }
      Thread.sleep(POLL_INTERVAL.toMillis());
    } while (Instant.now().isBefore(deadline));
    throw new AssertionError("run did not finish within " + TIMEOUT + "; last GET = " + last);
  }

  // A minimal-but-complete archive: metadata.yaml + a one-row NameUsage.tsv (so schema validation
  // passes and the project gets created -- ImportApiIT covers that path in detail) + a two-row
  // Reference.tsv exercising every ReferenceColdpWriter column (row "r1" has no accessed date but an
  // existing alternativeID CURIE; row "r2" carries an accessed date AND its own alternativeID CURIE)
  // + a one-row Author.tsv exercising every AuthorColdpWriter column, also with its own alternativeID
  // CURIE. preserveIds=true/idScope="src" means every one of those three rows should end up with a
  // SECOND, app-appended "src:<archive-row-ID>" CURIE alongside the archive's own.
  private byte[] buildArchive(Path dir) throws IOException {
    ColdpMetadata.write(dir, new ColdpMetadataDto("Ref+Author Checklist", null, null, null, null, null));

    Map<ColdpTerm, String> usageRow = new LinkedHashMap<>();
    usageRow.put(ColdpTerm.ID, "1");
    usageRow.put(ColdpTerm.scientificName, "Abies alba");
    usageRow.put(ColdpTerm.rank, "species");
    usageRow.put(ColdpTerm.status, "accepted");
    usageRow.put(ColdpTerm.code, "botanical");
    ColdpTsv.writeFile(dir, ColdpTerm.NameUsage, List.of(usageRow));

    Map<ColdpTerm, String> ref1 = new LinkedHashMap<>();
    ref1.put(ColdpTerm.ID, "r1");
    ref1.put(ColdpTerm.alternativeID, "doi:10.1000/xyz123");
    ref1.put(ColdpTerm.citation, "Smith, J. (2020) A paper on things. Journal of Things, 1(1), 1-10.");
    ref1.put(ColdpTerm.type, "article-journal");
    ref1.put(ColdpTerm.author, "Smith J");
    ref1.put(ColdpTerm.editor, "Editor E");
    ref1.put(ColdpTerm.title, "A paper on things");
    ref1.put(ColdpTerm.containerTitle, "Journal of Things");
    ref1.put(ColdpTerm.issued, "2020");
    ref1.put(ColdpTerm.volume, "1");
    ref1.put(ColdpTerm.issue, "1");
    ref1.put(ColdpTerm.page, "1-10");
    ref1.put(ColdpTerm.publisher, "Things Publishing");
    ref1.put(ColdpTerm.doi, "10.1000/xyz123");
    ref1.put(ColdpTerm.isbn, "978-3-16-148410-0");
    ref1.put(ColdpTerm.issn, "1234-5678");
    ref1.put(ColdpTerm.link, "https://example.org/xyz123");
    ref1.put(ColdpTerm.remarks, "a plain reference fixture");

    Map<ColdpTerm, String> ref2 = new LinkedHashMap<>();
    ref2.put(ColdpTerm.ID, "r2");
    ref2.put(ColdpTerm.alternativeID, "bibkey:doe2021");
    ref2.put(ColdpTerm.citation, "Doe, A. (2021) Another paper. Proc. Weird Stuff, 2, 5-9.");
    ref2.put(ColdpTerm.type, "article-journal");
    ref2.put(ColdpTerm.title, "Another paper");
    ref2.put(ColdpTerm.accessed, "2021-05-01");
    ColdpTsv.writeFile(dir, ColdpTerm.Reference, List.of(ref1, ref2));

    Map<ColdpTerm, String> author1 = new LinkedHashMap<>();
    author1.put(ColdpTerm.ID, "a1");
    author1.put(ColdpTerm.alternativeID, "wikidata:Q123456");
    author1.put(ColdpTerm.given, "Carl");
    author1.put(ColdpTerm.family, "Linnaeus");
    author1.put(ColdpTerm.suffix, "Jr.");
    author1.put(ColdpTerm.abbreviationBotany, "L.");
    author1.put(ColdpTerm.affiliation, "Uppsala University");
    author1.put(ColdpTerm.country, "SE");
    author1.put(ColdpTerm.birth, "1707-05-23");
    author1.put(ColdpTerm.birthPlace, "Rashult");
    author1.put(ColdpTerm.death, "1778-01-10");
    author1.put(ColdpTerm.link, "https://en.wikipedia.org/wiki/Carl_Linnaeus");
    author1.put(ColdpTerm.remarks, "father of taxonomy");
    ColdpTsv.writeFile(dir, ColdpTerm.Author, List.of(author1));

    Path zip = dir.resolveSibling(dir.getFileName() + ".zip");
    ColdpZip.zipFolder(dir, zip);
    return Files.readAllBytes(zip);
  }

  @Test
  @WithMockUser(username = "importRefAuthorOwner")
  void importsReferencesAndAuthorsWithPreservedSourceIds(@TempDir Path tmp) throws Exception {
    ensureUser("importRefAuthorOwner");

    Path dir = tmp.resolve("archive");
    Files.createDirectories(dir);
    byte[] zipBytes = buildArchive(dir);
    MockMultipartFile file = new MockMultipartFile("file", "refauthor.zip", "application/zip", zipBytes);

    String startBody = mvc.perform(multipart("/api/projects/import").file(file)
            .param("preserveIds", "true").param("idScope", "src").with(csrf()))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    long runId = json.readTree(startBody).get("id").asLong();

    JsonNode done = pollUntilTerminal(runId);
    assertThat(done.get("status").asString()).isEqualTo("DONE");
    assertThat(done.get("error").isNull()).isTrue();
    assertThat(done.get("referenceCount").asInt()).isEqualTo(2);
    assertThat(done.get("authorCount").asInt()).isEqualTo(1);
    // buildArchive's one-row NameUsage.tsv exists only to satisfy loadTransactional's "has usage
    // data" precondition -- Task 4 (ImportNameUsageIT/ImportSplitFormIT) is the real test of
    // name-usage loading; this just reflects that the row is now actually loaded, not skipped.
    assertThat(done.get("nameUsageCount").asInt()).isEqualTo(1);
    int projectId = done.get("projectId").asInt();

    List<Reference> refs = references.findAllByProject(projectId);
    assertThat(refs).hasSize(2);

    Reference r1 = refs.stream().filter(r -> "A paper on things".equals(r.getTitle())).findFirst().orElseThrow();
    assertThat(r1.getCitation()).isEqualTo("Smith, J. (2020) A paper on things. Journal of Things, 1(1), 1-10.");
    assertThat(r1.getType()).isEqualTo("article-journal");
    assertThat(r1.getAuthor()).isEqualTo("Smith J");
    assertThat(r1.getEditor()).isEqualTo("Editor E");
    assertThat(r1.getContainerTitle()).isEqualTo("Journal of Things");
    assertThat(r1.getIssued()).isEqualTo("2020");
    assertThat(r1.getVolume()).isEqualTo("1");
    assertThat(r1.getIssue()).isEqualTo("1");
    assertThat(r1.getPage()).isEqualTo("1-10");
    assertThat(r1.getPublisher()).isEqualTo("Things Publishing");
    assertThat(r1.getDoi()).isEqualTo("10.1000/xyz123");
    assertThat(r1.getIsbn()).isEqualTo("978-3-16-148410-0");
    assertThat(r1.getIssn()).isEqualTo("1234-5678");
    assertThat(r1.getLink()).isEqualTo("https://example.org/xyz123");
    assertThat(r1.getRemarks()).isEqualTo("a plain reference fixture");
    assertThat(r1.getAccessed()).isNull();
    assertThat(r1.getAlternativeId()).containsExactlyInAnyOrder("doi:10.1000/xyz123", "src:r1");

    Reference r2 = refs.stream().filter(r -> "Another paper".equals(r.getTitle())).findFirst().orElseThrow();
    assertThat(r2.getCitation()).isEqualTo("Doe, A. (2021) Another paper. Proc. Weird Stuff, 2, 5-9.");
    assertThat(r2.getAccessed()).isEqualTo("2021-05-01");
    assertThat(r2.getAlternativeId()).containsExactlyInAnyOrder("bibkey:doe2021", "src:r2");

    List<Author> createdAuthors = authors.findByProject(projectId);
    assertThat(createdAuthors).hasSize(1);
    Author a1 = createdAuthors.get(0);
    assertThat(a1.getGiven()).isEqualTo("Carl");
    assertThat(a1.getFamily()).isEqualTo("Linnaeus");
    assertThat(a1.getSuffix()).isEqualTo("Jr.");
    assertThat(a1.getAbbreviationBotany()).isEqualTo("L.");
    assertThat(a1.getAffiliation()).isEqualTo("Uppsala University");
    assertThat(a1.getCountry()).isEqualTo("SE");
    assertThat(a1.getBirth()).isEqualTo("1707-05-23");
    assertThat(a1.getBirthPlace()).isEqualTo("Rashult");
    assertThat(a1.getDeath()).isEqualTo("1778-01-10");
    assertThat(a1.getLink()).isEqualTo("https://en.wikipedia.org/wiki/Carl_Linnaeus");
    assertThat(a1.getRemarks()).isEqualTo("father of taxonomy");
    assertThat(a1.getAlternativeId()).containsExactlyInAnyOrder("wikidata:Q123456", "src:a1");
  }
}
