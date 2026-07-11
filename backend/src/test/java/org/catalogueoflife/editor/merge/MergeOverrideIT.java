package org.catalogueoflife.editor.merge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.catalogueoflife.editor.merge.dto.Category;
import org.catalogueoflife.editor.merge.dto.MergeOverride;
import org.catalogueoflife.editor.name.IdSeqMapper;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.parse.NameParserService;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.gbif.nameparser.api.NomCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Task 5's end-to-end proof of PUT .../merge/{runId}/overrides: a curator's confirm/reject/
// re-point corrections to individual Candidates on an already-PLANNED plan. Reuses MergePlanIT's
// "parse-then-allocate-then-insert" fixture pattern (Panthera/Panthera leo target classification)
// plus NameMatcherIT's "Panthera leoo" misspelling fixture (a deterministic POSSIBLE_FUZZY against
// the production 0.7 trigram-similarity threshold -- see NameMatcherIT's class comment) so this
// test drives a real compute-plan through MATCHED, POSSIBLE_FUZZY and NEW candidates before
// overriding two of them and asserting the metrics rollup shifts exactly as expected.
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MergeOverrideIT extends AbstractPostgresIT {

  private static final String ENTITY = "name_usage";
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;
  @Autowired NameUsageMapper usages;
  @Autowired IdSeqMapper idSeq;
  @Autowired NameParserService parser;
  @Autowired MergeRunMapper runs;

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

  private NameUsage newUsage(long projectId, int userId, String scientificName, String authorship,
      String rank, Status status) {
    NameUsage u = new NameUsage();
    u.setProjectId((int) projectId);
    u.setScientificName(scientificName);
    u.setAuthorship(authorship);
    u.setRank(rank);
    u.setStatus(status);
    u.setModifiedBy(userId);
    parser.parseInto(u, NomCode.ZOOLOGICAL);
    u.setId(idSeq.allocate((int) projectId, ENTITY));
    usages.insert(u);
    return u;
  }

  private JsonNode getRun(long targetId, long runId) throws Exception {
    String body = mvc.perform(get("/api/projects/" + targetId + "/merge/" + runId))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body);
  }

  // Same bounded-poll discipline as MergePlanIT.pollUntilTerminal.
  private JsonNode pollUntilTerminal(long targetId, long runId) throws Exception {
    Instant deadline = Instant.now().plus(TIMEOUT);
    JsonNode last;
    do {
      last = getRun(targetId, runId);
      if (!"RUNNING".equals(last.get("status").asString())) {
        return last;
      }
      Thread.sleep(POLL_INTERVAL.toMillis());
    } while (Instant.now().isBefore(deadline));
    throw new AssertionError("run did not reach PLANNED within " + TIMEOUT + "; last GET = " + last);
  }

  @Test
  @WithMockUser(username = "mergeOverrideOwner")
  void overridesRejectAndConfirmShiftMetrics() throws Exception {
    ensureUser("mergeOverrideOwner");
    int userId = users.requireByUsernameOrNull("mergeOverrideOwner").getId();

    long targetId = createProject("mergeOverrideTarget");
    long sourceId = createProject("mergeOverrideSource");

    newUsage(targetId, userId, "Panthera", null, "genus", Status.ACCEPTED);
    NameUsage targetLeo = newUsage(targetId, userId, "Panthera leo", "(Linnaeus, 1758)", "species", Status.ACCEPTED);

    // (1) exact canonical key + compatible author, ACCEPTED -> auto-MATCHED (to be rejected below).
    NameUsage srcMatched = newUsage(sourceId, userId, "Panthera leo", "(Linnaeus, 1758)", "species", Status.ACCEPTED);
    // (2) a misspelling, no exact candidate but a close trigram match -> POSSIBLE_FUZZY (to be
    // confirmed below). See NameMatcherIT's class comment for why this deterministically clears
    // the production 0.7 similarity threshold against "Panthera leo".
    NameUsage srcFuzzy = newUsage(sourceId, userId, "Panthera leoo", null, "species", Status.ACCEPTED);
    // (3) a genuinely new name, left untouched by any override.
    NameUsage srcNew = newUsage(sourceId, userId, "Panthera onca", "Linnaeus, 1758", "species", Status.ACCEPTED);

    String startBody = mvc.perform(post("/api/projects/" + targetId + "/merge")
            .with(csrf()).param("source", String.valueOf(sourceId)))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    long runId = json.readTree(startBody).get("id").asLong();

    JsonNode planned = pollUntilTerminal(targetId, runId);
    assertThat(planned.get("status").asString()).isEqualTo("PLANNED");

    // Sanity-check the pre-override baseline this test's shift assertions depend on.
    JsonNode baseline = planned.get("metrics").get("names");
    assertThat(baseline.get("new").asInt()).isEqualTo(1);
    assertThat(baseline.get("matched").asInt()).isEqualTo(1);
    assertThat(baseline.get("possibleFuzzy").asInt()).isEqualTo(1);
    assertThat(planned.get("metrics").get("newAccepted").asInt()).isEqualTo(1);

    List<MergeOverride> overrides = List.of(
        // Reject the auto-MATCHED name: MATCHED -> NEW, targetId forced null.
        new MergeOverride("name", String.valueOf(srcMatched.getId()), Category.NEW, null),
        // Confirm the POSSIBLE_FUZZY name: POSSIBLE_FUZZY -> MATCHED, curator-chosen targetId.
        new MergeOverride("name", String.valueOf(srcFuzzy.getId()), Category.MATCHED, String.valueOf(targetLeo.getId())));

    String putBody = mvc.perform(put("/api/projects/" + targetId + "/merge/" + runId + "/overrides")
            .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(overrides)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    JsonNode putResponse = json.readTree(putBody);
    assertThat(putResponse.get("status").asString()).isEqualTo("PLANNED");

    // Both the PUT's own response and a subsequent GET must reflect the shifted metrics:
    // new: 1 (srcNew) + 1 (srcMatched, rejected) = 2; matched: 1 (srcMatched, rejected) - 1 +
    // 1 (srcFuzzy, confirmed) = 1; possibleFuzzy: 1 - 1 (confirmed) = 0.
    for (JsonNode metricsHolder : List.of(putResponse, getRun(targetId, runId))) {
      JsonNode names = metricsHolder.get("metrics").get("names");
      assertThat(names.get("new").asInt()).isEqualTo(2);
      assertThat(names.get("matched").asInt()).isEqualTo(1);
      assertThat(names.get("possibleFuzzy").asInt()).isEqualTo(0);
      assertThat(names.get("possibleHomonym").asInt()).isEqualTo(0);
      // srcMatched was ACCEPTED, so its rejection adds another newAccepted (not newSynonyms).
      assertThat(metricsHolder.get("metrics").get("newAccepted").asInt()).isEqualTo(2);
      assertThat(metricsHolder.get("metrics").get("newSynonyms").asInt()).isEqualTo(0);
    }

    // The rejected candidate's mapping row now shows category NEW with no targetId; the confirmed
    // one shows MATCHED with the curator-chosen targetId.
    String matchedMapping = mvc.perform(get("/api/projects/" + targetId + "/merge/" + runId + "/mapping")
            .param("entity", "name").param("category", "MATCHED"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    JsonNode matchedRows = json.readTree(matchedMapping);
    assertThat(matchedRows).hasSize(1);
    assertThat(matchedRows.get(0).get("sourceId").asString()).isEqualTo(String.valueOf(srcFuzzy.getId()));
    assertThat(matchedRows.get(0).get("targetId").asString()).isEqualTo(String.valueOf(targetLeo.getId()));

    String newMapping = mvc.perform(get("/api/projects/" + targetId + "/merge/" + runId + "/mapping")
            .param("entity", "name").param("category", "NEW"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    JsonNode newRows = json.readTree(newMapping);
    assertThat(newRows).hasSize(2);
    for (JsonNode row : newRows) {
      assertThat(row.get("targetId").isNull()).isTrue();
    }
  }

  @Test
  @WithMockUser(username = "mergeOverrideBadTargetOwner")
  void matchedOverrideWithNonExistentTargetIdReturns400() throws Exception {
    ensureUser("mergeOverrideBadTargetOwner");
    int userId = users.requireByUsernameOrNull("mergeOverrideBadTargetOwner").getId();

    long targetId = createProject("mergeOverrideBadTargetTarget");
    long sourceId = createProject("mergeOverrideBadTargetSource");

    newUsage(targetId, userId, "Panthera", null, "genus", Status.ACCEPTED);
    NameUsage srcNew = newUsage(sourceId, userId, "Panthera onca", "Linnaeus, 1758", "species", Status.ACCEPTED);

    String startBody = mvc.perform(post("/api/projects/" + targetId + "/merge")
            .with(csrf()).param("source", String.valueOf(sourceId)))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    long runId = json.readTree(startBody).get("id").asLong();
    JsonNode planned = pollUntilTerminal(targetId, runId);
    assertThat(planned.get("status").asString()).isEqualTo("PLANNED");

    List<MergeOverride> badOverride = List.of(
        new MergeOverride("name", String.valueOf(srcNew.getId()), Category.MATCHED, "999999999"));

    mvc.perform(put("/api/projects/" + targetId + "/merge/" + runId + "/overrides")
            .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(badOverride)))
        .andExpect(status().isBadRequest());

    // No partial write: the plan/metrics stored for the run are untouched by the rejected batch.
    JsonNode after = getRun(targetId, runId);
    assertThat(after.get("metrics").get("names").get("new").asInt()).isEqualTo(1);
    assertThat(after.get("metrics").get("names").get("matched").asInt()).isEqualTo(0);
  }

  // merge_run_active_idx (V19__merge_run.sql) allows a RUNNING/APPLYING row to be inserted directly
  // via the mapper for a target with no prior run -- same shortcut MergePlanIT's conflict test uses
  // -- so overriding a plan that was never computed (still RUNNING, no plan JSON at all) 409s
  // rather than NPE-ing on a null plan.
  @Test
  @WithMockUser(username = "mergeOverrideWrongStatusOwner")
  void overridingANonPlannedRunReturns409() throws Exception {
    ensureUser("mergeOverrideWrongStatusOwner");
    int userId = users.requireByUsernameOrNull("mergeOverrideWrongStatusOwner").getId();
    long targetId = createProject("mergeOverrideWrongStatusTarget");
    long sourceId = createProject("mergeOverrideWrongStatusSource");

    MergeRun stillRunning = new MergeRun();
    stillRunning.setUserId(userId);
    stillRunning.setSourceProjectId(sourceId);
    stillRunning.setTargetProjectId(targetId);
    runs.insertRunning(stillRunning);

    List<MergeOverride> overrides = List.of(new MergeOverride("name", "1", Category.NEW, null));

    mvc.perform(put("/api/projects/" + targetId + "/merge/" + stillRunning.getId() + "/overrides")
            .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(overrides)))
        .andExpect(status().isConflict());
  }
}
