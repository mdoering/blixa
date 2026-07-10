package org.catalogueoflife.editor.coldp.imprt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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
import life.catalogue.api.vocab.License;
import life.catalogue.coldp.ColdpTerm;
import org.catalogueoflife.editor.coldp.io.ColdpMetadata;
import org.catalogueoflife.editor.coldp.io.ColdpMetadata.ColdpMetadataDto;
import org.catalogueoflife.editor.coldp.io.ColdpTsv;
import org.catalogueoflife.editor.coldp.io.ColdpZip;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectMapper;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.gbif.nameparser.api.NomCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Task 2's end-to-end proof of the async import trigger: POST kicks off ImportRunService.start
// (which extracts the upload synchronously, then fires the @Async run() through the self-proxy --
// see ImportAsyncConfig/ImportRunService), returns 202 immediately; GET polls the same row until it
// reaches a terminal state. Unlike ExportRunApiIT (which downloads a produced zip), the interesting
// assertion here is on the SIDE EFFECT: a new project, created from the archive's metadata.yaml +
// the nomenclatural code peeked off the first data row's `code` column. No test-profile synchronous
// executor is introduced -- exactly like ExportRunApiIT, this polls the real single-thread
// ImportAsyncConfig.EXECUTOR_BEAN pool rather than faking synchronicity.
//
// coldp.import.max-bytes is lowered to a small, deterministic value for this whole test class (a
// separate Spring context from the application-default one, cached independently) so the oversize
// test can trip the real 413 path with an ordinary small byte array, instead of needing an actual
// 100MB+ upload.
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "coldp.import.max-bytes=20000")
class ImportApiIT extends AbstractPostgresIT {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;
  @Autowired ProjectMapper projects;

  private void ensureUser(String username) {
    AppUser existing = users.requireByUsernameOrNull(username);
    if (existing == null) users.createLocal(username, "pw", username);
  }

  // Builds a minimal-but-valid ColDP archive (metadata.yaml + a one-row NameUsage.tsv carrying
  // `code`) directly under dir, zips it (flat, non-recursive -- see ColdpZip's javadoc), and returns
  // the zip bytes ready to attach as a multipart file part.
  private byte[] buildArchive(Path dir, ColdpMetadataDto metadata, String code) throws IOException {
    ColdpMetadata.write(dir, metadata);
    Map<ColdpTerm, String> row = new LinkedHashMap<>();
    row.put(ColdpTerm.ID, "1");
    row.put(ColdpTerm.scientificName, "Abies alba");
    row.put(ColdpTerm.rank, "species");
    row.put(ColdpTerm.status, "accepted");
    row.put(ColdpTerm.code, code);
    ColdpTsv.writeFile(dir, ColdpTerm.NameUsage, List.of(row));

    Path zip = dir.resolveSibling(dir.getFileName() + ".zip");
    ColdpZip.zipFolder(dir, zip);
    return Files.readAllBytes(zip);
  }

  private JsonNode getRun(long runId) throws Exception {
    String body = mvc.perform(get("/api/projects/import/" + runId))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body);
  }

  // Bounded, deterministic wait for the async run to leave RUNNING -- same discipline as
  // ExportRunApiIT.pollUntilTerminal: poll the real GET endpoint on a short fixed interval, fail
  // loudly with the last-seen row instead of hanging or racily asserting right after the POST.
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

  @Test
  @WithMockUser(username = "importRunOwner")
  void startsAndCompletesAnImportCreatingAProjectFromMetadataAndCode(@TempDir Path tmp) throws Exception {
    ensureUser("importRunOwner");

    Path dir = tmp.resolve("archive");
    Files.createDirectories(dir);
    byte[] zipBytes = buildArchive(dir,
        new ColdpMetadataDto("Imported Checklist", "IMP", "a test description", "CC0-1.0",
            "global", "all taxa"),
        "zoological");
    MockMultipartFile file = new MockMultipartFile("file", "imported.zip", "application/zip", zipBytes);

    String startBody = mvc.perform(multipart("/api/projects/import").file(file).with(csrf()))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    JsonNode started = json.readTree(startBody);
    assertThat(started.get("id").isNull()).isFalse();
    assertThat(started.get("status").asString()).isNotEqualTo("FAILED");
    assertThat(started.get("sourceName").asString()).isEqualTo("imported.zip");
    long runId = started.get("id").asLong();

    JsonNode done = pollUntilTerminal(runId);
    assertThat(done.get("status").asString()).isEqualTo("DONE");
    assertThat(done.get("error").isNull()).isTrue();
    assertThat(done.get("projectId").isNull()).isFalse();
    long projectId = done.get("projectId").asLong();

    Project p = projects.findById((int) projectId);
    assertThat(p).isNotNull();
    assertThat(p.getTitle()).isEqualTo("Imported Checklist");
    assertThat(p.getAlias()).isEqualTo("IMP");
    assertThat(p.getDescription()).isEqualTo("a test description");
    assertThat(p.getLicense()).isEqualTo(License.CC0);
    assertThat(p.getGeographicScope()).isEqualTo("global");
    assertThat(p.getTaxonomicScope()).isEqualTo("all taxa");
    assertThat(p.getNomCode()).isEqualTo(NomCode.ZOOLOGICAL);

    String latestBody = mvc.perform(get("/api/projects/import/latest"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    JsonNode latest = json.readTree(latestBody);
    assertThat(latest.get("id").asLong()).isEqualTo(runId);
    assertThat(latest.get("status").asString()).isEqualTo("DONE");
  }

  // Only metadata.yaml, no data file of any kind -- ColdpReader.from(dir) itself (CsvReader's
  // schema discovery, the CLB library code, unmodified) throws a SourceInvalidException("No data
  // files found in ...") before loadTransactional's own NameUsage/Name+Taxon check ever runs. Either
  // way the row ends up FAILED with a clear, non-blank message rather than stuck RUNNING or an
  // unhandled 500 -- see the sibling test below for the case that DOES reach our own check.
  @Test
  @WithMockUser(username = "importRunNoDataFiles")
  void archiveWithNoDataFilesAtAllFailsTheRunWithAClearMessage(@TempDir Path tmp) throws Exception {
    ensureUser("importRunNoDataFiles");

    Path dir = tmp.resolve("archive");
    Files.createDirectories(dir);
    ColdpMetadata.write(dir, new ColdpMetadataDto("No Data Checklist", null, null, null, null, null));
    Path zip = tmp.resolve("nodata.zip");
    ColdpZip.zipFolder(dir, zip);
    byte[] zipBytes = Files.readAllBytes(zip);
    MockMultipartFile file = new MockMultipartFile("file", "nodata.zip", "application/zip", zipBytes);

    String startBody = mvc.perform(multipart("/api/projects/import").file(file).with(csrf()))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    long runId = json.readTree(startBody).get("id").asLong();

    JsonNode done = pollUntilTerminal(runId);
    assertThat(done.get("status").asString()).isEqualTo("FAILED");
    assertThat(done.get("error").asString()).containsIgnoringCase("no data files found");
    assertThat(done.get("projectId").isNull()).isTrue();
  }

  // A recognized ColDP data file IS present (Reference.tsv, so ColdpReader.from's own schema
  // discovery succeeds) but neither NameUsage nor Name+Taxon -- this is the archive shape
  // loadTransactional's own explicit requirement check (added for this task) rejects, distinct from
  // the "no data files at all" case above which never reaches that check.
  @Test
  @WithMockUser(username = "importRunNoUsageFile")
  void archiveMissingNameUsageAndNameTaxonFailsTheRunWithAClearMessage(@TempDir Path tmp) throws Exception {
    ensureUser("importRunNoUsageFile");

    Path dir = tmp.resolve("archive");
    Files.createDirectories(dir);
    ColdpMetadata.write(dir, new ColdpMetadataDto("Refs Only Checklist", null, null, null, null, null));
    Map<ColdpTerm, String> refRow = new LinkedHashMap<>();
    refRow.put(ColdpTerm.ID, "r1");
    refRow.put(ColdpTerm.citation, "Some Author (2020) A paper.");
    ColdpTsv.writeFile(dir, ColdpTerm.Reference, List.of(refRow));
    Path zip = tmp.resolve("refsonly.zip");
    ColdpZip.zipFolder(dir, zip);
    byte[] zipBytes = Files.readAllBytes(zip);
    MockMultipartFile file = new MockMultipartFile("file", "refsonly.zip", "application/zip", zipBytes);

    String startBody = mvc.perform(multipart("/api/projects/import").file(file).with(csrf()))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    long runId = json.readTree(startBody).get("id").asLong();

    JsonNode done = pollUntilTerminal(runId);
    assertThat(done.get("status").asString()).isEqualTo("FAILED");
    assertThat(done.get("error").asString()).contains("NameUsage");
    assertThat(done.get("projectId").isNull()).isTrue();
  }

  // ImportRunService.start's pre-check (file.getSize() > maxBytes) rejects an oversize upload
  // before it ever reaches ColdpZip -- the file doesn't even need to be a real zip for this to fire.
  @Test
  @WithMockUser(username = "importRunOversize")
  void oversizeUploadIsRejectedWith413() throws Exception {
    ensureUser("importRunOversize");

    byte[] tooBig = new byte[25_000]; // > this test class's 20000-byte coldp.import.max-bytes
    MockMultipartFile file = new MockMultipartFile("file", "huge.zip", "application/zip", tooBig);

    mvc.perform(multipart("/api/projects/import").file(file).with(csrf()))
        .andExpect(status().isPayloadTooLarge());
  }

  // get()'s "never leak another user's import" contract: a runId that genuinely exists, but was
  // started by a different user, 404s exactly like one that doesn't exist at all.
  @Test
  @WithMockUser(username = "importRunOwnerB")
  void getReturns404ForAnotherUsersRun(@TempDir Path tmp) throws Exception {
    ensureUser("importRunOwnerB");
    ensureUser("importRunOwnerC");

    Path dir = tmp.resolve("archive");
    Files.createDirectories(dir);
    byte[] zipBytes = buildArchive(dir,
        new ColdpMetadataDto("Owner B's Checklist", null, null, null, null, null), null);
    MockMultipartFile file = new MockMultipartFile("file", "b.zip", "application/zip", zipBytes);

    String startBody = mvc.perform(multipart("/api/projects/import").file(file).with(csrf()))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    long runId = json.readTree(startBody).get("id").asLong();

    mvc.perform(get("/api/projects/import/" + runId).with(user("importRunOwnerC")))
        .andExpect(status().isNotFound());
  }
}
