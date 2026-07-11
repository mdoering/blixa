package org.catalogueoflife.editor.merge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.catalogueoflife.editor.child.DistributionMapper;
import org.catalogueoflife.editor.child.dto.DistributionRequest;
import org.catalogueoflife.editor.child.dto.DistributionResponse;
import org.catalogueoflife.editor.name.IdSeqMapper;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.name.SynonymAcceptedMapper;
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

// Task 7's end-to-end proof that apply's Mode reconciliation of MATCHED records actually branches
// on `mode`, using the SAME shape of source+target fixture applied fresh (its own target/source
// project pair, since apply consumes a run once) under each of the three Modes.
//
// Fixture, identical across the three project pairs below:
//  - target: genus "Panthera" (ACCEPTED) + species "Panthera leo" (ACCEPTED, parent = genus,
//    authorship BLANK, remarks = "target original remark") + synonym "Felis leo" (SYNONYM, no
//    existing synonym_accepted link).
//  - source: the same genus + "Panthera leo" (authorship "(Linnaeus, 1758)", remarks = "source
//    override remark", ONE Distribution child area="Africa") + the same "Felis leo" (linked via
//    synonym_accepted to the source leo) + a brand-new accepted "Panthera onca".
//
// NameMatcher.authorCompatible only ever calls two DIFFERING non-blank author strings compatible
// when at least one side is blank (mismatched non-blank authors -> POSSIBLE_HOMONYM, never
// MATCHED, by design -- see NameMatcher's own javadoc) -- so a MATCHED usage with a genuinely
// differing, curator-visible authorship-reconciliation outcome needs the TARGET side blank. That is
// exactly `authorship` here: blank on the target, non-blank on the source, so BOTH OVERWRITE and
// FILL_GAPS end up filling it to the source's value (a "blank target field filled from source"
// demonstration for FILL_GAPS, and incidentally also satisfies OVERWRITE's "authorship is now the
// source value"). `remarks` is the field that actually DISTINGUISHES OVERWRITE from FILL_GAPS: both
// sides are non-blank and differ, so OVERWRITE replaces it and FILL_GAPS keeps the target's own.
// "Felis leo" being a pre-existing MATCHED target usage (not a brand-new source-only synonym) is
// what exercises Step 2's matched-synonym-link reconciliation (a NEW synonym's own link is always
// added regardless of mode -- see Mode's javadoc -- so it would not distinguish NEW_ONLY at all).
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MergeApplyModesIT extends AbstractPostgresIT {

  private static final String ENTITY = "name_usage";
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;
  @Autowired NameUsageMapper usages;
  @Autowired SynonymAcceptedMapper synonymAccepted;
  @Autowired DistributionMapper distributions;
  @Autowired IdSeqMapper idSeq;
  @Autowired NameParserService parser;

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
      String rank, Status status, Integer parentId, String remarks) {
    NameUsage u = new NameUsage();
    u.setProjectId((int) projectId);
    u.setScientificName(scientificName);
    u.setAuthorship(authorship);
    u.setRank(rank);
    u.setStatus(status);
    u.setParentId(parentId);
    u.setRemarks(remarks);
    u.setModifiedBy(userId);
    parser.parseInto(u, NomCode.ZOOLOGICAL);
    u.setId(idSeq.allocate((int) projectId, ENTITY));
    usages.insert(u);
    return u;
  }

  private void newDistribution(long projectId, int userId, int usageId, String area) {
    int id = idSeq.allocate((int) projectId, "distribution");
    distributions.insert((int) projectId, id, usageId,
        new DistributionRequest(area, null, null, null, null, null, null, null), userId);
  }

  private JsonNode getRun(long targetId, long runId) throws Exception {
    String body = mvc.perform(get("/api/projects/" + targetId + "/merge/" + runId))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body);
  }

  private JsonNode pollUntilTerminal(long targetId, long runId) throws Exception {
    Instant deadline = Instant.now().plus(TIMEOUT);
    JsonNode last;
    do {
      last = getRun(targetId, runId);
      String st = last.get("status").asString();
      if (!"RUNNING".equals(st) && !"APPLYING".equals(st)) {
        return last;
      }
      Thread.sleep(POLL_INTERVAL.toMillis());
    } while (Instant.now().isBefore(deadline));
    throw new AssertionError("run did not leave RUNNING/APPLYING within " + TIMEOUT + "; last GET = " + last);
  }

  private record Fixture(long targetId, long sourceId, NameUsage targetGenus, NameUsage targetLeo,
      NameUsage targetFelisLeo, NameUsage srcLeo, NameUsage srcFelisLeo) {}

  private Fixture buildFixture(String tag, int userId) throws Exception {
    long targetId = createProject("modes" + tag + "Target");
    long sourceId = createProject("modes" + tag + "Source");

    NameUsage targetGenus = newUsage(targetId, userId, "Panthera", null, "genus", Status.ACCEPTED, null, null);
    NameUsage targetLeo = newUsage(targetId, userId, "Panthera leo", null, "species", Status.ACCEPTED,
        targetGenus.getId(), "target original remark");
    NameUsage targetFelisLeo = newUsage(targetId, userId, "Felis leo", null, "species", Status.SYNONYM,
        null, null);

    NameUsage srcGenus = newUsage(sourceId, userId, "Panthera", null, "genus", Status.ACCEPTED, null, null);
    NameUsage srcLeo = newUsage(sourceId, userId, "Panthera leo", "(Linnaeus, 1758)", "species",
        Status.ACCEPTED, srcGenus.getId(), "source override remark");
    newDistribution(sourceId, userId, srcLeo.getId(), "Africa");
    NameUsage srcFelisLeo = newUsage(sourceId, userId, "Felis leo", null, "species", Status.SYNONYM,
        null, null);
    synonymAccepted.link((int) sourceId, srcFelisLeo.getId(), srcLeo.getId(), 0);
    newUsage(sourceId, userId, "Panthera onca", "Linnaeus, 1758", "species", Status.ACCEPTED,
        srcGenus.getId(), null);

    return new Fixture(targetId, sourceId, targetGenus, targetLeo, targetFelisLeo, srcLeo, srcFelisLeo);
  }

  private JsonNode computePlan(long targetId, long sourceId) throws Exception {
    String startBody = mvc.perform(post("/api/projects/" + targetId + "/merge")
            .with(csrf()).param("source", String.valueOf(sourceId)))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    long runId = json.readTree(startBody).get("id").asLong();
    JsonNode planned = pollUntilTerminal(targetId, runId);
    assertThat(planned.get("status").asString()).isEqualTo("PLANNED");
    // Sanity: genus + leo + Felis leo all MATCHED, only "Panthera onca" NEW -- confirms the fixture
    // actually exercises the matched-reconciliation path this test targets, not an accidental miss.
    assertThat(planned.get("metrics").get("names").get("matched").asInt()).isEqualTo(3);
    assertThat(planned.get("metrics").get("names").get("new").asInt()).isEqualTo(1);
    return planned;
  }

  private JsonNode apply(long targetId, long runId, String mode) throws Exception {
    String applyBody = mvc.perform(post("/api/projects/" + targetId + "/merge/" + runId + "/apply")
            .with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"mode\":\"" + mode + "\",\"transactional\":true}"))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    assertThat(json.readTree(applyBody).get("status").asString()).isNotEqualTo("FAILED");
    JsonNode done = pollUntilTerminal(targetId, runId);
    assertThat(done.get("status").asString()).isEqualTo("DONE");
    return done;
  }

  @Test
  @WithMockUser(username = "mergeModesOverwrite")
  void overwriteReconcilesMatchedScalarsAndAddsRelations() throws Exception {
    ensureUser("mergeModesOverwrite");
    int userId = users.requireByUsernameOrNull("mergeModesOverwrite").getId();
    Fixture f = buildFixture("Overwrite", userId);

    JsonNode planned = computePlan(f.targetId(), f.sourceId());
    long runId = planned.get("id").asLong();
    apply(f.targetId(), runId, "OVERWRITE");

    NameUsage updatedLeo = usages.findByIdInProject((int) f.targetId(), f.targetLeo().getId());
    assertThat(updatedLeo.getAuthorship()).isEqualTo("(Linnaeus, 1758)");
    assertThat(updatedLeo.getRemarks()).isEqualTo("source override remark");
    assertThat(updatedLeo.getAlternativeId()).contains("src:" + f.srcLeo().getId());

    List<DistributionResponse> leoDistributions =
        distributions.findByUsage((int) f.targetId(), f.targetLeo().getId());
    assertThat(leoDistributions).hasSize(1);
    assertThat(leoDistributions.get(0).area()).isEqualTo("Africa");

    NameUsage updatedFelisLeo = usages.findByIdInProject((int) f.targetId(), f.targetFelisLeo().getId());
    assertThat(updatedFelisLeo.getAlternativeId()).contains("src:" + f.srcFelisLeo().getId());
    List<Integer> acceptedOfFelisLeo =
        synonymAccepted.findAcceptedFor((int) f.targetId(), f.targetFelisLeo().getId());
    assertThat(acceptedOfFelisLeo).containsExactly(f.targetLeo().getId());

    boolean oncaAdded = usages.findAllByProject((int) f.targetId()).stream()
        .anyMatch(u -> "Panthera onca".equals(u.getScientificName()));
    assertThat(oncaAdded).isTrue();
  }

  @Test
  @WithMockUser(username = "mergeModesFillGaps")
  void fillGapsFillsBlanksKeepsNonBlankAndAddsRelations() throws Exception {
    ensureUser("mergeModesFillGaps");
    int userId = users.requireByUsernameOrNull("mergeModesFillGaps").getId();
    Fixture f = buildFixture("FillGaps", userId);

    JsonNode planned = computePlan(f.targetId(), f.sourceId());
    long runId = planned.get("id").asLong();
    apply(f.targetId(), runId, "FILL_GAPS");

    NameUsage updatedLeo = usages.findByIdInProject((int) f.targetId(), f.targetLeo().getId());
    // Blank target field filled from source.
    assertThat(updatedLeo.getAuthorship()).isEqualTo("(Linnaeus, 1758)");
    // Non-blank target value KEPT, never overwritten.
    assertThat(updatedLeo.getRemarks()).isEqualTo("target original remark");
    assertThat(updatedLeo.getAlternativeId()).contains("src:" + f.srcLeo().getId());

    List<DistributionResponse> leoDistributions =
        distributions.findByUsage((int) f.targetId(), f.targetLeo().getId());
    assertThat(leoDistributions).hasSize(1);
    assertThat(leoDistributions.get(0).area()).isEqualTo("Africa");

    NameUsage updatedFelisLeo = usages.findByIdInProject((int) f.targetId(), f.targetFelisLeo().getId());
    assertThat(updatedFelisLeo.getAlternativeId()).contains("src:" + f.srcFelisLeo().getId());
    List<Integer> acceptedOfFelisLeo =
        synonymAccepted.findAcceptedFor((int) f.targetId(), f.targetFelisLeo().getId());
    assertThat(acceptedOfFelisLeo).containsExactly(f.targetLeo().getId());

    boolean oncaAdded = usages.findAllByProject((int) f.targetId()).stream()
        .anyMatch(u -> "Panthera onca".equals(u.getScientificName()));
    assertThat(oncaAdded).isTrue();
  }

  @Test
  @WithMockUser(username = "mergeModesNewOnly")
  void newOnlyLeavesMatchedRecordsCompletelyUntouched() throws Exception {
    ensureUser("mergeModesNewOnly");
    int userId = users.requireByUsernameOrNull("mergeModesNewOnly").getId();
    Fixture f = buildFixture("NewOnly", userId);

    NameUsage leoBefore = usages.findByIdInProject((int) f.targetId(), f.targetLeo().getId());
    NameUsage felisLeoBefore = usages.findByIdInProject((int) f.targetId(), f.targetFelisLeo().getId());

    JsonNode planned = computePlan(f.targetId(), f.sourceId());
    long runId = planned.get("id").asLong();
    apply(f.targetId(), runId, "NEW_ONLY");

    NameUsage leoAfter = usages.findByIdInProject((int) f.targetId(), f.targetLeo().getId());
    // Completely untouched: authorship, remarks, alternative_id (no src: CURIE), and even the
    // optimistic-lock version/modified stamp -- byte-for-byte, not merely "same visible fields".
    assertThat(leoAfter.getAuthorship()).isNull();
    assertThat(leoAfter.getRemarks()).isEqualTo("target original remark");
    assertThat(leoAfter.getAlternativeId()).isEqualTo(leoBefore.getAlternativeId());
    assertThat(leoAfter.getAlternativeId() == null
        || !leoAfter.getAlternativeId().contains("src:" + f.srcLeo().getId())).isTrue();
    assertThat(leoAfter.getVersion()).isEqualTo(leoBefore.getVersion());
    assertThat(leoAfter.getModified()).isEqualTo(leoBefore.getModified());

    // No child added.
    List<DistributionResponse> leoDistributions =
        distributions.findByUsage((int) f.targetId(), f.targetLeo().getId());
    assertThat(leoDistributions).isEmpty();

    // No relation added to the matched synonym.
    NameUsage felisLeoAfter = usages.findByIdInProject((int) f.targetId(), f.targetFelisLeo().getId());
    assertThat(felisLeoAfter.getVersion()).isEqualTo(felisLeoBefore.getVersion());
    assertThat(felisLeoAfter.getAlternativeId()).isEqualTo(felisLeoBefore.getAlternativeId());
    List<Integer> acceptedOfFelisLeo =
        synonymAccepted.findAcceptedFor((int) f.targetId(), f.targetFelisLeo().getId());
    assertThat(acceptedOfFelisLeo).isEmpty();

    // Only the NEW name was added.
    boolean oncaAdded = usages.findAllByProject((int) f.targetId()).stream()
        .anyMatch(u -> "Panthera onca".equals(u.getScientificName()));
    assertThat(oncaAdded).isTrue();
  }
}
