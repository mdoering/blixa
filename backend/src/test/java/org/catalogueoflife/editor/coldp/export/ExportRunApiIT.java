package org.catalogueoflife.editor.coldp.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.catalogueoflife.editor.coldp.io.ColdpMetadata;
import org.catalogueoflife.editor.coldp.io.ColdpZip;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Task 1's end-to-end proof of the async export trigger + polling + download endpoints: POST kicks
// off ExportRunService.start (which fires the @Async run() through the self-proxy -- see
// ExportAsyncConfig/ExportRunService), returns 202 immediately with a RUNNING (or, if the tiny job
// already raced ahead, DONE) row; GET polls the same row until it reaches a terminal state; GET
// .../file streams the produced zip. No test-profile synchronous executor is introduced -- exactly
// like ColMatchRunApiIT's bounded pollUntilTerminal loop, this polls the real single-thread
// ExportAsyncConfig.EXECUTOR_BEAN pool rather than faking synchronity. In this task the archive
// contains only metadata.yaml (ColdpWriter.write); later tasks add entity .tsv files to the same
// zip without changing this test's shape.
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExportRunApiIT extends AbstractPostgresIT {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;
  @Autowired ExportRunMapper runs;

  private void ensureUser(String username) {
    AppUser existing = users.requireByUsernameOrNull(username);
    if (existing == null) users.createLocal(username, "pw", username);
  }

  private long createProject(String title) throws Exception {
    String body = mvc.perform(post("/api/projects").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"" + title + "\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  private JsonNode getRun(long pid, long runId) throws Exception {
    String body = mvc.perform(get("/api/projects/" + pid + "/export/" + runId))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body);
  }

  // Bounded, deterministic wait for the async run to leave RUNNING -- same discipline as
  // ColMatchRunApiIT.pollUntilTerminal: poll the real GET endpoint on a short fixed interval, fail
  // loudly with the last-seen row instead of hanging or racily asserting right after the POST.
  private JsonNode pollUntilTerminal(long pid, long runId) throws Exception {
    Instant deadline = Instant.now().plus(TIMEOUT);
    JsonNode last;
    do {
      last = getRun(pid, runId);
      if (!"RUNNING".equals(last.get("status").asString())) {
        return last;
      }
      Thread.sleep(POLL_INTERVAL.toMillis());
    } while (Instant.now().isBefore(deadline));
    throw new AssertionError("run did not finish within " + TIMEOUT + "; last GET = " + last);
  }

  @Test
  @WithMockUser(username = "exportRunOwner")
  void startsAndCompletesAnExportProducingADownloadableZip(@TempDir Path tmp) throws Exception {
    ensureUser("exportRunOwner");
    long pid = createProject("exportrunproj");

    // no run yet -- 204, not 404 (the project itself exists and is visible to this member).
    mvc.perform(get("/api/projects/" + pid + "/export/latest"))
        .andExpect(status().isNoContent());

    String startBody = mvc.perform(post("/api/projects/" + pid + "/export").with(csrf()))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    JsonNode started = json.readTree(startBody);
    assertThat(started.get("id").isNull()).isFalse();
    assertThat(started.get("status").asString()).isNotEqualTo("FAILED");
    long runId = started.get("id").asLong();

    JsonNode done = pollUntilTerminal(pid, runId);
    assertThat(done.get("status").asString()).isEqualTo("DONE");
    assertThat(done.get("fileSize").asLong()).isGreaterThan(0);
    assertThat(done.get("fileName").asString()).endsWith("-coldp.zip");

    byte[] zipBytes = mvc.perform(get("/api/projects/" + pid + "/export/" + runId + "/file"))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
            .string("Content-Type", "application/zip"))
        .andReturn().getResponse().getContentAsByteArray();
    assertThat(zipBytes.length).isGreaterThan(0);

    Path zipFile = tmp.resolve("export.zip");
    Files.write(zipFile, zipBytes);
    Path extractDir = tmp.resolve("extracted");
    try (InputStream in = Files.newInputStream(zipFile)) {
      ColdpZip.extractToTemp(in, extractDir);
    }
    assertThat(extractDir.resolve("metadata.yaml")).exists();
    ColdpMetadata.ColdpMetadataDto metadata = ColdpMetadata.read(extractDir);
    assertThat(metadata.title()).isEqualTo("exportrunproj");

    String latestBody = mvc.perform(get("/api/projects/" + pid + "/export/latest"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    JsonNode latest = json.readTree(latestBody);
    assertThat(latest.get("id").asLong()).isEqualTo(runId);
    assertThat(latest.get("status").asString()).isEqualTo("DONE");
  }

  // One-active-run-per-project guard (ExportRunService.start's findRunningByProject pre-check).
  // Inserts a RUNNING row directly via the mapper -- exactly like ColMatchRunApiIT does -- rather
  // than racing the real async executor, so the 409 is asserted deterministically instead of
  // depending on a still-RUNNING window after a real POST.
  @Test
  @WithMockUser(username = "exportRunConflictOwner")
  void startReturns409WhileAnExportIsAlreadyInProgress() throws Exception {
    ensureUser("exportRunConflictOwner");
    long pid = createProject("exportrunconflictproj");

    ExportRun alreadyRunning = new ExportRun();
    alreadyRunning.setProjectId((int) pid);
    runs.insertRunning(alreadyRunning);

    mvc.perform(post("/api/projects/" + pid + "/export").with(csrf()))
        .andExpect(status().isConflict());
  }
}
