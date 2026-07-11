package org.catalogueoflife.editor.merge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.catalogueoflife.editor.name.IdSeqMapper;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.Reference;
import org.catalogueoflife.editor.name.ReferenceMapper;
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

// Task 6's end-to-end proof of POST .../merge/{runId}/apply: drives a full compute-plan ->
// PLANNED -> apply (mode OVERWRITE, transactional true) -> DONE round trip through the real HTTP
// endpoints (MergePlanIT/MergeOverrideIT's identical "parse-then-allocate-then-insert" fixture
// pattern, reused verbatim), then asserts every apply-time invariant MergeApplyService.applyWorker
// claims to uphold:
//
//  - a MATCHED source usage (Panthera leo) keeps the TARGET's existing id and gains a src:<id>
//    provenance CURIE -- id-stability is the whole point of a supervised merge.
//  - a NEW accepted usage (Panthera onca) whose source parent MATCHED (the source Panthera genus)
//    lands under the TARGET parent's id -- "attach at nearest matched ancestor" for the direct case.
//  - a NEW accepted usage (Panthera onca hernandesii) whose source parent is ALSO new resolves
//    transitively onto that sibling new usage's target id -- the two-level chain case.
//  - a NEW synonym (Felis leo) with a synonym_accepted link to a MATCHED accepted (Panthera leo)
//    gets a target-side synonym_accepted link (parent_id stays null -- the status inverse).
//  - refIdMap remaps correctly both for a MATCHED reference (provenance stamped, no scalar change)
//    and for a NEW usage's publishedInReferenceId pointing at a NEW reference (never the source's
//    own reference id, which means nothing in the target project).
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MergeApplyNamesIT extends AbstractPostgresIT {

  private static final String ENTITY = "name_usage";
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;
  @Autowired NameUsageMapper usages;
  @Autowired ReferenceMapper references;
  @Autowired SynonymAcceptedMapper synonymAccepted;
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

  // Same parse-then-allocate-then-insert sequence as MergePlanIT.newUsage, extended with
  // parentId/publishedInReferenceId so the fixture can build a real (if tiny) classification and
  // reference-citing usages directly via the mappers -- both are ordinary insert() columns, so
  // there's no forward-reference problem building the tree top-down (parent inserted, and its real
  // id known, before any of its children).
  private NameUsage newUsage(long projectId, int userId, String scientificName, String authorship,
      String rank, Status status, Integer parentId, Integer publishedInReferenceId) {
    NameUsage u = new NameUsage();
    u.setProjectId((int) projectId);
    u.setScientificName(scientificName);
    u.setAuthorship(authorship);
    u.setRank(rank);
    u.setStatus(status);
    u.setParentId(parentId);
    u.setPublishedInReferenceId(publishedInReferenceId);
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

  // Bounded, deterministic wait for either async phase (compute-plan's RUNNING or apply's
  // APPLYING) to leave its in-flight state -- same discipline as MergePlanIT.pollUntilTerminal,
  // generalized to cover both merge_run phases this test drives through in sequence.
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

  @Test
  @WithMockUser(username = "mergeApplyOwner")
  void applyKeepsMatchedIdsAndClassifiesNewUsages() throws Exception {
    ensureUser("mergeApplyOwner");
    int userId = users.requireByUsernameOrNull("mergeApplyOwner").getId();

    long targetId = createProject("mergeApplyTarget");
    long sourceId = createProject("mergeApplySource");

    // Target: genus + type species (both ACCEPTED) plus one DOI-bearing reference -- the classification
    // and reference the source below is merged into.
    NameUsage targetGenus = newUsage(targetId, userId, "Panthera", null, "genus", Status.ACCEPTED, null, null);
    NameUsage targetLeo = newUsage(targetId, userId, "Panthera leo", "(Linnaeus, 1758)", "species",
        Status.ACCEPTED, targetGenus.getId(), null);
    Reference targetRef = newReference(targetId, userId,
        "Doe, J. 2020. A DOI title. Journal, 1, 2-3.", "10.1234/abcd");

    // Source: genus + species that both MATCH the target's (same canonical key + compatible author
    // -- see NameMatcher.authorCompatible), a brand-new accepted species under the matched genus, a
    // brand-new accepted subspecies under THAT new species (two-level new chain), and a brand-new
    // synonym pointing at the matched species. Plus one matched (DOI) and one new reference.
    NameUsage srcGenus = newUsage(sourceId, userId, "Panthera", null, "genus", Status.ACCEPTED, null, null);
    NameUsage srcLeo = newUsage(sourceId, userId, "Panthera leo", "(Linnaeus, 1758)", "species",
        Status.ACCEPTED, srcGenus.getId(), null);

    Reference srcMatchedRef = newReference(sourceId, userId,
        "Doe, J. 2020. An unrelated citation string that matches nothing by text.",
        "https://doi.org/10.1234/abcd");
    Reference srcNewRef = newReference(sourceId, userId,
        "Zephyrus, Q. 2099. Something totally unrelated about quantum toast. Nowhere Press.", null);

    NameUsage srcOnca = newUsage(sourceId, userId, "Panthera onca", "Linnaeus, 1758", "species",
        Status.ACCEPTED, srcGenus.getId(), srcNewRef.getId());
    NameUsage srcOncaSub = newUsage(sourceId, userId, "Panthera onca hernandesii", "Goldman, 1925",
        "subspecies", Status.ACCEPTED, srcOnca.getId(), null);
    NameUsage srcFelisLeo = newUsage(sourceId, userId, "Felis leo", "Linnaeus, 1758", "species",
        Status.SYNONYM, null, null);
    synonymAccepted.link((int) sourceId, srcFelisLeo.getId(), srcLeo.getId(), 0);

    // Compute-plan -> PLANNED.
    String startBody = mvc.perform(post("/api/projects/" + targetId + "/merge")
            .with(csrf()).param("source", String.valueOf(sourceId)))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    long runId = json.readTree(startBody).get("id").asLong();
    JsonNode planned = pollUntilTerminal(targetId, runId);
    assertThat(planned.get("status").asString()).isEqualTo("PLANNED");
    // Sanity-check the plan matched what the fixture intends before trusting apply's output below.
    assertThat(planned.get("metrics").get("names").get("matched").asInt()).isEqualTo(2); // genus + leo
    assertThat(planned.get("metrics").get("names").get("new").asInt()).isEqualTo(3); // onca, subspecies, synonym
    assertThat(planned.get("metrics").get("references").get("matched").asInt()).isEqualTo(1);
    assertThat(planned.get("metrics").get("references").get("new").asInt()).isEqualTo(1);

    // Apply (OVERWRITE, transactional) -> DONE.
    String applyBody = mvc.perform(post("/api/projects/" + targetId + "/merge/" + runId + "/apply")
            .with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"mode\":\"OVERWRITE\",\"transactional\":true}"))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    assertThat(json.readTree(applyBody).get("status").asString()).isNotEqualTo("FAILED");
    JsonNode done = pollUntilTerminal(targetId, runId);
    assertThat(done.get("status").asString()).isEqualTo("DONE");

    // (a) MATCHED: the target keeps its own id and gains a src:<sourceId> provenance CURIE; no
    // other scalar changed (Task 6 never reconciles a matched usage's fields).
    NameUsage updatedTargetLeo = usages.findByIdInProject((int) targetId, targetLeo.getId());
    assertThat(updatedTargetLeo).isNotNull();
    assertThat(updatedTargetLeo.getAlternativeId()).contains("src:" + srcLeo.getId());
    assertThat(updatedTargetLeo.getAuthorship()).isEqualTo("(Linnaeus, 1758)");

    List<NameUsage> targetUsages = usages.findAllByProject((int) targetId);
    // 2 original (genus, leo) + 3 new (onca, subspecies, synonym).
    assertThat(targetUsages).hasSize(5);

    // (b) NEW accepted under a MATCHED parent: Panthera onca's target parent_id is the TARGET
    // genus's id (not the source genus's id) -- "attach at nearest matched ancestor".
    NameUsage targetOnca = targetUsages.stream()
        .filter(u -> "Panthera onca".equals(u.getScientificName()))
        .findFirst().orElseThrow(() -> new AssertionError("Panthera onca not found in target"));
    assertThat(targetOnca.getParentId()).isEqualTo(targetGenus.getId());
    assertThat(targetOnca.getAlternativeId()).contains("src:" + srcOnca.getId());

    // (c) NEW accepted whose parent is ALSO new: the subspecies resolves onto the sibling new
    // usage's freshly-allocated target id, not the source id.
    NameUsage targetOncaSub = targetUsages.stream()
        .filter(u -> "Panthera onca hernandesii".equals(u.getScientificName()))
        .findFirst().orElseThrow(() -> new AssertionError("Panthera onca hernandesii not found in target"));
    assertThat(targetOncaSub.getParentId()).isEqualTo(targetOnca.getId());

    // (d) NEW synonym with a synonym_accepted link to a MATCHED accepted: parent_id stays null (the
    // status inverse -- synonyms never get a parent_id), and a target-side synonym_accepted link
    // points at the target's own (matched, id-stable) Panthera leo.
    NameUsage targetFelisLeo = targetUsages.stream()
        .filter(u -> "Felis leo".equals(u.getScientificName()))
        .findFirst().orElseThrow(() -> new AssertionError("Felis leo not found in target"));
    assertThat(targetFelisLeo.getParentId()).isNull();
    assertThat(targetFelisLeo.getStatus()).isEqualTo(Status.SYNONYM);
    List<Integer> acceptedOfFelisLeo =
        synonymAccepted.findAcceptedFor((int) targetId, targetFelisLeo.getId());
    assertThat(acceptedOfFelisLeo).containsExactly(targetLeo.getId());

    // (e) refIdMap remap: the matched reference keeps its target id + gains provenance (scalars
    // untouched), and the NEW usage's publishedInReferenceId resolves to the NEW target reference
    // (never the source's own reference id, which is meaningless in the target project).
    Reference updatedTargetRef = references.findByIdInProject((int) targetId, targetRef.getId());
    assertThat(updatedTargetRef).isNotNull();
    assertThat(updatedTargetRef.getAlternativeId()).contains("src:" + srcMatchedRef.getId());
    assertThat(updatedTargetRef.getCitation()).isEqualTo(targetRef.getCitation());

    List<Reference> targetRefs = references.findAllByProject((int) targetId);
    assertThat(targetRefs).hasSize(2); // original DOI ref + the new one
    Reference targetNewRef = targetRefs.stream()
        .filter(r -> r.getAlternativeId() != null && r.getAlternativeId().contains("src:" + srcNewRef.getId()))
        .findFirst().orElseThrow(() -> new AssertionError("new reference not found in target"));
    assertThat(targetOnca.getPublishedInReferenceId()).isEqualTo(targetNewRef.getId());
  }
}
