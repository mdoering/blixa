package org.catalogueoflife.editor.merge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.catalogueoflife.editor.name.IdSeqMapper;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.Reference;
import org.catalogueoflife.editor.name.ReferenceMapper;
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

// Task 4's end-to-end proof of the merge compute-plan job + its endpoints: POST kicks off
// MergeService.start (fires the @Async computePlan through the self-proxy -- see
// MergeAsyncConfig/MergeService), returns 202 immediately with a RUNNING (or, if the tiny job
// already raced ahead, PLANNED) row; GET polls the same row until it reaches PLANNED and asserts
// the impact metrics; GET .../mapping pages the stored plan's candidates with display labels. No
// test-profile synchronous executor is introduced -- exactly like ColMatchRunApiIT/ExportRunApiIT's
// bounded pollUntilTerminal loop, this polls the real single-thread MergeAsyncConfig.EXECUTOR_BEAN
// pool rather than faking synchronity.
//
// Fixture shapes reuse NameMatcherIT (Panthera/Panthera leo target classification, one usage per
// Category) and ReferenceMatcherIT (a DOI/citation target reference) directly via the mappers --
// same "parse-then-allocate-then-insert" sequence NameUsageService.create/ReferenceService.create
// use -- rather than round-tripping every fixture row through MockMvc, so each Category this test
// cares about is deterministic (no trigram-fuzzy thresholds involved; POSSIBLE_FUZZY is already
// covered by NameMatcherIT/ReferenceMatcherIT and is orthogonal to what this task adds: the job
// wiring, the metrics rollup, and the mapping endpoint). The acting user owns BOTH projects --
// MergeService.start requires owner/editor on the target and only membership (any role) on the
// source, and a single curator merging two of their own projects satisfies both.
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MergePlanIT extends AbstractPostgresIT {

  private static final String ENTITY = "name_usage";
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;
  @Autowired NameUsageMapper usages;
  @Autowired ReferenceMapper references;
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

  // Same PUT .../members shape as ColMatchRunApiIT.addMember.
  private void addMember(long pid, String username, String role) throws Exception {
    mvc.perform(put("/api/projects/" + pid + "/members").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"role\":\"" + role + "\"}"))
        .andExpect(status().isOk());
  }

  // Same parse-then-allocate-then-insert sequence as NameMatcherIT.newUsage, so the atomized
  // name-part fields NameMatcher.canonicalKey reads are populated exactly as a normal create would.
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

  private Reference newReference(long projectId, int userId, String citation, String doi) {
    Reference r = new Reference();
    r.setProjectId((int) projectId);
    r.setCitation(citation);
    r.setDoi(doi);
    r.setModifiedBy(userId);
    r.setId(idSeq.allocate((int) projectId, "reference"));
    references.insert(r);
    return r;
  }

  private JsonNode getRun(long targetId, long runId) throws Exception {
    String body = mvc.perform(get("/api/projects/" + targetId + "/merge/" + runId))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body);
  }

  // Bounded, deterministic wait for the async compute-plan to leave RUNNING -- same discipline as
  // ColMatchRunApiIT/ExportRunApiIT's pollUntilTerminal: poll the real GET endpoint on a short fixed
  // interval, fail loudly with the last-seen row instead of hanging or racily asserting right after
  // the POST.
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
  @WithMockUser(username = "mergePlanOwner")
  void computePlanProducesExpectedMetricsAndPagedMapping() throws Exception {
    ensureUser("mergePlanOwner");
    int userId = users.requireByUsernameOrNull("mergePlanOwner").getId();

    long targetId = createProject("mergePlanTarget");
    long sourceId = createProject("mergePlanSource");

    // Target: a small real classification -- the genus plus its type species, authored -- plus one
    // DOI-bearing reference (see ReferenceMatcherIT's identical fixture shape).
    newUsage(targetId, userId, "Panthera", null, "genus", Status.ACCEPTED);
    NameUsage targetLeo = newUsage(targetId, userId, "Panthera leo", "(Linnaeus, 1758)", "species", Status.ACCEPTED);
    Reference targetRef = newReference(targetId, userId,
        "Doe, J. 2020. A DOI title. Journal, 1, 2-3.", "10.1234/abcd");

    // Source: one usage per Category this task's metrics rollup cares about.
    // (1) exact same canonical key + compatible author, ACCEPTED -> MATCHED.
    NameUsage srcMatched = newUsage(sourceId, userId, "Panthera leo", "(Linnaeus, 1758)", "species", Status.ACCEPTED);
    // (2) same canonical key, incompatible author -> POSSIBLE_HOMONYM.
    newUsage(sourceId, userId, "Panthera leo", "Smith, 1900", "species", Status.ACCEPTED);
    // (3) a genuinely new ACCEPTED name, no candidate at all -> NEW, counted under newAccepted.
    NameUsage srcNewAccepted = newUsage(sourceId, userId, "Panthera onca", "Linnaeus, 1758", "species", Status.ACCEPTED);
    // (4) a genuinely new SYNONYM, no candidate at all -> NEW, counted under newSynonyms.
    NameUsage srcNewSynonym = newUsage(sourceId, userId, "Felis catus domestica", "Linnaeus, 1758", "subspecies", Status.SYNONYM);

    // Source references: one that matches the target's DOI reference, one that matches nothing.
    Reference srcMatchedRef = newReference(sourceId, userId,
        "Doe, J. 2020. An unrelated citation string that matches nothing by text.",
        "https://doi.org/10.1234/abcd");
    Reference srcNewRef = newReference(sourceId, userId,
        "Zephyrus, Q. 2099. Something totally unrelated about quantum toast. Nowhere Press.", null);

    String startBody = mvc.perform(post("/api/projects/" + targetId + "/merge")
            .with(csrf()).param("source", String.valueOf(sourceId)))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    JsonNode started = json.readTree(startBody);
    assertThat(started.get("id").isNull()).isFalse();
    assertThat(started.get("status").asString()).isNotEqualTo("FAILED");
    long runId = started.get("id").asLong();

    JsonNode planned = pollUntilTerminal(targetId, runId);
    assertThat(planned.get("status").asString()).isEqualTo("PLANNED");

    JsonNode metrics = planned.get("metrics");
    JsonNode names = metrics.get("names");
    assertThat(names.get("new").asInt()).isEqualTo(2);
    assertThat(names.get("matched").asInt()).isEqualTo(1);
    assertThat(names.get("possibleHomonym").asInt()).isEqualTo(1);
    assertThat(names.get("possibleFuzzy").asInt()).isEqualTo(0);

    JsonNode refs = metrics.get("references");
    assertThat(refs.get("new").asInt()).isEqualTo(1);
    assertThat(refs.get("matched").asInt()).isEqualTo(1);
    assertThat(refs.get("possible").asInt()).isEqualTo(0);

    assertThat(metrics.get("newAccepted").asInt()).isEqualTo(1);
    assertThat(metrics.get("newSynonyms").asInt()).isEqualTo(1);
    assertThat(metrics.get("unanchored").asInt()).isEqualTo(0);

    // GET .../mapping?entity=name&category=NEW -- both NEW rows, with display labels populated
    // from the source usage (targetLabel null: NEW candidates carry no targetId).
    String mappingBody = mvc.perform(get("/api/projects/" + targetId + "/merge/" + runId + "/mapping")
            .param("entity", "name").param("category", "NEW"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    JsonNode mappingRows = json.readTree(mappingBody);
    assertThat(mappingRows).hasSize(2);
    Set<String> newSourceIds = new HashSet<>();
    for (JsonNode row : mappingRows) {
      assertThat(row.get("category").asString()).isEqualTo("NEW");
      assertThat(row.get("targetId").isNull()).isTrue();
      assertThat(row.get("sourceLabel").asString()).isNotBlank();
      newSourceIds.add(row.get("sourceId").asString());
    }
    assertThat(newSourceIds).containsExactlyInAnyOrder(
        String.valueOf(srcNewAccepted.getId()), String.valueOf(srcNewSynonym.getId()));

    // GET .../mapping?entity=name&category=MATCHED -- the one MATCHED row, both labels populated.
    String matchedBody = mvc.perform(get("/api/projects/" + targetId + "/merge/" + runId + "/mapping")
            .param("entity", "name").param("category", "MATCHED"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    JsonNode matchedRows = json.readTree(matchedBody);
    assertThat(matchedRows).hasSize(1);
    JsonNode matchedRow = matchedRows.get(0);
    assertThat(matchedRow.get("sourceId").asString()).isEqualTo(String.valueOf(srcMatched.getId()));
    assertThat(matchedRow.get("targetId").asString()).isEqualTo(String.valueOf(targetLeo.getId()));
    assertThat(matchedRow.get("sourceLabel").asString()).contains("Panthera leo");
    assertThat(matchedRow.get("targetLabel").asString()).contains("Panthera leo");

    // GET .../mapping?entity=reference (no category filter) -- both reference rows, citations as labels.
    String refMappingBody = mvc.perform(get("/api/projects/" + targetId + "/merge/" + runId + "/mapping")
            .param("entity", "reference"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    JsonNode refRows = json.readTree(refMappingBody);
    assertThat(refRows).hasSize(2);
    for (JsonNode row : refRows) {
      if (row.get("sourceId").asString().equals(String.valueOf(srcMatchedRef.getId()))) {
        assertThat(row.get("category").asString()).isEqualTo("MATCHED");
        assertThat(row.get("targetLabel").asString()).isEqualTo(targetRef.getCitation());
      } else {
        assertThat(row.get("sourceId").asString()).isEqualTo(String.valueOf(srcNewRef.getId()));
        assertThat(row.get("category").asString()).isEqualTo("NEW");
        assertThat(row.get("targetLabel").isNull()).isTrue();
      }
    }
  }

  @Test
  @WithMockUser(username = "mergePlanRejectOwner")
  void startRejectsSourceEqualsTarget() throws Exception {
    ensureUser("mergePlanRejectOwner");
    long targetId = createProject("mergePlanSelfTarget");

    mvc.perform(post("/api/projects/" + targetId + "/merge")
            .with(csrf()).param("source", String.valueOf(targetId)))
        .andExpect(status().isBadRequest());
  }

  // One-active-run-per-target guard (MergeService.start's findActiveByTarget pre-check).
  // merge_run_active_idx (V19__merge_run.sql) locks on RUNNING/APPLYING only -- NOT PLANNED (see
  // its comment) -- so this asserts the 409 by inserting a RUNNING row directly via the mapper
  // (exactly like ColMatchRunApiIT/ExportRunApiIT do) rather than racing the real async executor.
  @Test
  @WithMockUser(username = "mergePlanConflictOwner")
  void startReturns409WhileAMergeIsAlreadyInProgress() throws Exception {
    ensureUser("mergePlanConflictOwner");
    int userId = users.requireByUsernameOrNull("mergePlanConflictOwner").getId();
    long targetId = createProject("mergePlanConflictTarget");
    long sourceId = createProject("mergePlanConflictSource");

    MergeRun alreadyRunning = new MergeRun();
    alreadyRunning.setUserId(userId);
    alreadyRunning.setSourceProjectId(sourceId);
    alreadyRunning.setTargetProjectId(targetId);
    runs.insertRunning(alreadyRunning);

    mvc.perform(post("/api/projects/" + targetId + "/merge")
            .with(csrf()).param("source", String.valueOf(sourceId)))
        .andExpect(status().isConflict());
  }

  // MergeService.getMapping's paging must be past-the-end-safe: page=100/size=50 on a plan with a
  // handful of name candidates used to compute from=5000, well past filtered.size(), and
  // List.subList(from, to) threw IndexOutOfBoundsException -- an uncaught 500 -- instead of the
  // documented "page past the end -> empty" behaviour. Drives a real compute-plan through to
  // PLANNED (one NEW name candidate is enough to make filtered.size() > 0, so the assertion
  // actually exercises the past-the-end branch rather than the trivially-empty from==to==0 case).
  @Test
  @WithMockUser(username = "mergePlanPagingOwner")
  void mappingPagePastTheEndReturnsEmptyNot500() throws Exception {
    ensureUser("mergePlanPagingOwner");
    int userId = users.requireByUsernameOrNull("mergePlanPagingOwner").getId();
    long targetId = createProject("mergePlanPagingTarget");
    long sourceId = createProject("mergePlanPagingSource");

    newUsage(targetId, userId, "Panthera", null, "genus", Status.ACCEPTED);
    newUsage(sourceId, userId, "Panthera onca", "Linnaeus, 1758", "species", Status.ACCEPTED);

    String startBody = mvc.perform(post("/api/projects/" + targetId + "/merge")
            .with(csrf()).param("source", String.valueOf(sourceId)))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    long runId = json.readTree(startBody).get("id").asLong();

    JsonNode planned = pollUntilTerminal(targetId, runId);
    assertThat(planned.get("status").asString()).isEqualTo("PLANNED");

    String pastEndBody = mvc.perform(get("/api/projects/" + targetId + "/merge/" + runId + "/mapping")
            .param("entity", "name").param("page", "100").param("size", "50"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    JsonNode pastEndRows = json.readTree(pastEndBody);
    assertThat(pastEndRows.isArray()).isTrue();
    assertThat(pastEndRows).isEmpty();
  }

  // requireRunInTarget's 404 (runId exists but belongs to a DIFFERENT target project) must never
  // leak that run's data through either GET endpoint -- get() and getMapping() both route through
  // requireRunInTarget, so both are asserted here. A directly-inserted run (same shortcut as
  // startReturns409WhileAMergeIsAlreadyInProgress above) is enough since this only exercises the
  // target-ownership lookup, not the async compute-plan pipeline.
  @Test
  @WithMockUser(username = "mergePlanLeakOwner")
  void getAndMappingReturn404ForRunBelongingToAnotherTarget() throws Exception {
    ensureUser("mergePlanLeakOwner");
    int userId = users.requireByUsernameOrNull("mergePlanLeakOwner").getId();
    long ownTargetId = createProject("mergePlanLeakOwnTarget");
    long otherTargetId = createProject("mergePlanLeakOtherTarget");
    long otherSourceId = createProject("mergePlanLeakOtherSource");

    MergeRun otherRun = new MergeRun();
    otherRun.setUserId(userId);
    otherRun.setSourceProjectId(otherSourceId);
    otherRun.setTargetProjectId(otherTargetId);
    runs.insertRunning(otherRun);
    long otherRunId = otherRun.getId();

    mvc.perform(get("/api/projects/" + ownTargetId + "/merge/" + otherRunId))
        .andExpect(status().isNotFound());

    mvc.perform(get("/api/projects/" + ownTargetId + "/merge/" + otherRunId + "/mapping")
            .param("entity", "name"))
        .andExpect(status().isNotFound());
  }

  // Fix 2 (final-review, source-data leak): GET .../mapping returns SOURCE-project name/authorship/
  // citation strings, so it must be gated at the same owner/editor tier as the merge WRITE workflow
  // (start/overrides/apply), not the "any member may read" tier get()/latest() use -- a target-only
  // VIEWER could otherwise enumerate the source project's contents merely by polling this endpoint.
  // Same "addMember + .with(user(...))" pattern as ColMatchRunApiIT.viewerCannotStartAMatchRunButOwnerCan.
  @Test
  @WithMockUser(username = "mergePlanMappingPermOwner")
  void viewerCannotReadTheMappingButOwnerCan() throws Exception {
    ensureUser("mergePlanMappingPermOwner");
    ensureUser("mergePlanMappingPermViewer");
    int userId = users.requireByUsernameOrNull("mergePlanMappingPermOwner").getId();
    long targetId = createProject("mergePlanMappingPermTarget");
    long sourceId = createProject("mergePlanMappingPermSource");
    addMember(targetId, "mergePlanMappingPermViewer", "viewer");

    newUsage(targetId, userId, "Panthera", null, "genus", Status.ACCEPTED);
    newUsage(sourceId, userId, "Panthera onca", "Linnaeus, 1758", "species", Status.ACCEPTED);

    String startBody = mvc.perform(post("/api/projects/" + targetId + "/merge")
            .with(csrf()).param("source", String.valueOf(sourceId)))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    long runId = json.readTree(startBody).get("id").asLong();
    JsonNode planned = pollUntilTerminal(targetId, runId);
    assertThat(planned.get("status").asString()).isEqualTo("PLANNED");

    // A target VIEWER may still poll the run's metrics (get()/latest() stay any-member) but not read
    // the mapping's source-project display labels.
    mvc.perform(get("/api/projects/" + targetId + "/merge/" + runId).with(user("mergePlanMappingPermViewer")))
        .andExpect(status().isOk());
    mvc.perform(get("/api/projects/" + targetId + "/merge/" + runId + "/mapping")
            .param("entity", "name").with(user("mergePlanMappingPermViewer")))
        .andExpect(status().isForbidden());

    // The owner (who started the run) can still read it, proving the gate isn't over-broad.
    mvc.perform(get("/api/projects/" + targetId + "/merge/" + runId + "/mapping")
            .param("entity", "name"))
        .andExpect(status().isOk());
  }
}
