package org.catalogueoflife.editor.coldp.imprt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// End-to-end proof of the source-format-adapter seam (Task 6): a .txtree upload runs through the
// SAME async import -> staging-project pipeline as a ColDP archive (ImportRunService.start ->
// SourceFormat.detect -> TxtTreeAdapter.materialize -> self.run -> loadTransactional), landing as
// a real staging project with the accepted/synonym linkage intact -- not just a row count. This
// project has no org.awaitility dependency (see pom.xml), so this mirrors ImportApiIT's own
// bounded, fixed-interval poll (pollUntilTerminal) rather than introducing Awaitility.
@AutoConfigureMockMvc
class TxtTreeImportIT extends AbstractPostgresIT {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

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

  // Same discipline as ImportApiIT.pollUntilTerminal: poll the real GET endpoint on a short fixed
  // interval, fail loudly with the last-seen row instead of hanging or racily asserting right after
  // the POST.
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
  @WithMockUser(username = "ttImp")
  void importsTextTreeAsStagingProject() throws Exception {
    ensureUser("ttImp");
    String tree = "Panthera [genus]\n  Panthera leo [species]\n    =Felis leo\n";
    MockMultipartFile file = new MockMultipartFile("file", "cats.txtree",
        "text/plain", tree.getBytes(StandardCharsets.UTF_8));

    String started = mvc.perform(multipart("/api/projects/import").file(file)
            .param("title", "Cats").with(csrf()))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("RUNNING"))
        .andReturn().getResponse().getContentAsString();
    long runId = json.readTree(started).get("id").asLong();

    JsonNode done = pollUntilTerminal(runId);
    assertThat(done.get("status").asString()).isEqualTo("DONE");
    assertThat(done.get("error").isNull()).isTrue();
    assertThat(done.get("nameUsageCount").asInt()).isEqualTo(3);
    int projectId = done.get("projectId").asInt();

    // The created staging project has the genus as a root, "Panthera leo" as its accepted child,
    // and -- the actual point of this test, closing a gap TxtTreeToColdpTest's unit test can't
    // cover -- "Felis leo" REALLY linked as a synonym_accepted of "Panthera leo" via the importer's
    // parentID resolution, not merely present as a third, unrelated row.
    String usagesBody = mvc.perform(get("/api/projects/" + projectId + "/usages"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(3))
        .andReturn().getResponse().getContentAsString();
    JsonNode items = json.readTree(usagesBody).get("items");
    Integer pantheraId = null;
    Integer leoId = null;
    for (JsonNode item : items) {
      String name = item.get("scientificName").asString();
      if ("Panthera".equals(name)) {
        pantheraId = item.get("id").asInt();
        assertThat(item.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(item.get("parentId").isNull()).isTrue();
      } else if ("Panthera leo".equals(name)) {
        leoId = item.get("id").asInt();
        assertThat(item.get("status").asString()).isEqualTo("ACCEPTED");
      }
    }
    assertThat(pantheraId).as("Panthera root usage").isNotNull();
    assertThat(leoId).as("Panthera leo usage").isNotNull();

    // Trigram search (nu.scientific_name % q, ordered by similarity DESC) also picks up "Panthera"
    // as a looser match for the query "Panthera leo" -- the exact match still ranks first, which is
    // what this asserts, rather than an exact total that would be coupled to pg_trgm's threshold.
    mvc.perform(get("/api/projects/" + projectId + "/usages").param("q", "Panthera leo"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(leoId))
        .andExpect(jsonPath("$.items[0].scientificName").value("Panthera leo"))
        .andExpect(jsonPath("$.items[0].parentId").value(pantheraId));

    String synonymsBody = mvc.perform(get("/api/projects/" + projectId + "/usages/" + leoId + "/synonyms"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    JsonNode synonyms = json.readTree(synonymsBody);
    assertThat(synonyms.size()).isEqualTo(1);
    assertThat(synonyms.get(0).get("scientificName").asString()).isEqualTo("Felis leo");
  }
}
