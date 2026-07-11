package org.catalogueoflife.editor.merge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.catalogueoflife.editor.merge.dto.Candidate;
import org.catalogueoflife.editor.merge.dto.Category;
import org.catalogueoflife.editor.merge.dto.MergePlan;
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

  // Same shape as newUsage above, extended with a referenceId[] list -- newUsage has no parameter
  // for it since only this fix's referenceId[]-remap test needs to set it directly (every other
  // fixture in this file only ever exercises publishedInReferenceId).
  private NameUsage newUsageWithReferenceIds(long projectId, int userId, String scientificName,
      String rank, Status status, Integer parentId, List<Integer> referenceIds) {
    NameUsage u = new NameUsage();
    u.setProjectId((int) projectId);
    u.setScientificName(scientificName);
    u.setRank(rank);
    u.setStatus(status);
    u.setParentId(parentId);
    u.setReferenceId(referenceIds);
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

    // (e) refIdMap remap: the matched reference keeps its target id + gains provenance, and (Task 7:
    // mode OVERWRITE reconciles a matched record's scalars -- see MergeApplyService.reconcileMatchedRef)
    // its citation is now the SOURCE's citation, since the two differ and OVERWRITE is source-wins.
    // The NEW usage's publishedInReferenceId resolves to the NEW target reference (never the
    // source's own reference id, which is meaningless in the target project).
    Reference updatedTargetRef = references.findByIdInProject((int) targetId, targetRef.getId());
    assertThat(updatedTargetRef).isNotNull();
    assertThat(updatedTargetRef.getAlternativeId()).contains("src:" + srcMatchedRef.getId());
    assertThat(updatedTargetRef.getCitation()).isEqualTo(srcMatchedRef.getCitation());

    List<Reference> targetRefs = references.findAllByProject((int) targetId);
    assertThat(targetRefs).hasSize(2); // original DOI ref + the new one
    Reference targetNewRef = targetRefs.stream()
        .filter(r -> r.getAlternativeId() != null && r.getAlternativeId().contains("src:" + srcNewRef.getId()))
        .findFirst().orElseThrow(() -> new AssertionError("new reference not found in target"));
    assertThat(targetOnca.getPublishedInReferenceId()).isEqualTo(targetNewRef.getId());
  }

  // Fix 3 (review): a NEW accepted usage whose SOURCE parent maps to nothing must not blow up the
  // apply or leave a dangling parent_id -- it becomes a new ROOT (parent_id NULL) and the run's
  // issues carry an "unanchored" entry naming it. A normally-computed plan can never itself produce
  // this: every source usage is visited by applyNameUsages' Pass 1 and always ends up in usageIdMap
  // (matched -> existing target id, or else -> freshly-inserted NEW id) -- the ONLY way for a
  // parent's own srcId to be absent from usageIdMap is Fix 2's parseTargetId guard skipping it (a
  // MATCHED candidate with a null/blank/non-numeric targetId). So the plan is hand-crafted here (via
  // MergeRunMapper.updatePlan, bypassing PUT .../overrides' targetId-must-exist validation -- see
  // MergeOverrideIT.matchedOverrideWithNonExistentTargetIdReturns400) to simulate exactly that stale/
  // corrupted stored-plan row, forcing the parent's candidate to MATCHED with a null targetId.
  @Test
  @WithMockUser(username = "mergeApplyUnanchoredOwner")
  void applyLeavesUnresolvableParentAsRootWithUnanchoredIssue() throws Exception {
    ensureUser("mergeApplyUnanchoredOwner");
    int userId = users.requireByUsernameOrNull("mergeApplyUnanchoredOwner").getId();

    long targetId = createProject("mergeApplyUnanchoredTarget");
    long sourceId = createProject("mergeApplyUnanchoredSource");

    NameUsage srcParent = newUsage(sourceId, userId, "Bogus parentus", null, "genus", Status.ACCEPTED, null, null);
    NameUsage srcChild = newUsage(sourceId, userId, "Bogus parentus childus", null, "species", Status.ACCEPTED,
        srcParent.getId(), null);

    String startBody = mvc.perform(post("/api/projects/" + targetId + "/merge")
            .with(csrf()).param("source", String.valueOf(sourceId)))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    long runId = json.readTree(startBody).get("id").asLong();
    JsonNode planned = pollUntilTerminal(targetId, runId);
    assertThat(planned.get("status").asString()).isEqualTo("PLANNED");

    // Overwrite the stored plan directly: srcParent is forced MATCHED with a null targetId
    // (simulating a corrupted/stale plan row that Fix 2's guard must survive rather than
    // NumberFormatException-crash the whole apply). srcChild is left out of the plan entirely --
    // applyNameUsages already treats "no candidate at all" the same as NEW.
    MergePlan corrupted = new MergePlan(List.of(),
        List.of(new Candidate(String.valueOf(srcParent.getId()), Category.MATCHED, null, null)));
    int updated = runs.updatePlan(runId, json.writeValueAsString(corrupted));
    assertThat(updated).isEqualTo(1);

    String applyBody = mvc.perform(post("/api/projects/" + targetId + "/merge/" + runId + "/apply")
            .with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"mode\":\"OVERWRITE\",\"transactional\":true}"))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    assertThat(json.readTree(applyBody).get("status").asString()).isNotEqualTo("FAILED");
    JsonNode done = pollUntilTerminal(targetId, runId);
    assertThat(done.get("status").asString()).isEqualTo("DONE");

    List<NameUsage> targetUsages = usages.findAllByProject((int) targetId);
    // srcParent was skipped (MATCHED, no valid target id) -- never inserted; only srcChild landed.
    assertThat(targetUsages).hasSize(1);
    NameUsage targetChild = targetUsages.get(0);
    assertThat(targetChild.getScientificName()).isEqualTo("Bogus parentus childus");
    assertThat(targetChild.getParentId()).isNull();

    JsonNode issues = done.get("issues");
    assertThat(issues).isNotNull();
    assertThat(issues.isArray()).isTrue();
    boolean hasUnanchoredIssue = false;
    for (JsonNode issue : issues) {
      if (issue.get("message").asString().startsWith("unanchored:")
          && String.valueOf(srcChild.getId()).equals(issue.get("sourceId").asString())) {
        hasUnanchoredIssue = true;
      }
    }
    assertThat(hasUnanchoredIssue).as("unanchored issue for the orphaned child").isTrue();
  }

  // Fix 3 (review): a NEW usage's reference_id[] array (not just publishedInReferenceId) must remap
  // every resolvable entry through refIdMap -- matched-or-new -- in order, and silently drop (with an
  // issue) any entry that resolves to nothing, exactly like publishedInReferenceId's own handling a
  // few lines above buildNewUsage.
  @Test
  @WithMockUser(username = "mergeApplyRefArrayOwner")
  void applyRemapsReferenceIdArrayDroppingUnresolvedEntries() throws Exception {
    ensureUser("mergeApplyRefArrayOwner");
    int userId = users.requireByUsernameOrNull("mergeApplyRefArrayOwner").getId();

    long targetId = createProject("mergeApplyRefArrayTarget");
    long sourceId = createProject("mergeApplyRefArraySource");

    Reference targetRefA = newReference(targetId, userId,
        "Roe, R. 2021. A citation matched by DOI. Journal, 4, 5-6.", "10.4321/wxyz");

    Reference srcRefA = newReference(sourceId, userId,
        "Roe, R. 2021. An unrelated citation string that matches nothing by text.",
        "https://doi.org/10.4321/WXYZ");
    Reference srcRefB = newReference(sourceId, userId,
        "Zed, Z. 2088. Something else entirely unrelated. Nowhere Press.", null);
    int unresolvableRefId = 999999;

    NameUsage srcUsage = newUsageWithReferenceIds(sourceId, userId, "Refarrayus testus", "species",
        Status.ACCEPTED, null, List.of(srcRefA.getId(), srcRefB.getId(), unresolvableRefId));

    String startBody = mvc.perform(post("/api/projects/" + targetId + "/merge")
            .with(csrf()).param("source", String.valueOf(sourceId)))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    long runId = json.readTree(startBody).get("id").asLong();
    JsonNode planned = pollUntilTerminal(targetId, runId);
    assertThat(planned.get("status").asString()).isEqualTo("PLANNED");
    assertThat(planned.get("metrics").get("references").get("matched").asInt()).isEqualTo(1);
    assertThat(planned.get("metrics").get("references").get("new").asInt()).isEqualTo(1);

    String applyBody = mvc.perform(post("/api/projects/" + targetId + "/merge/" + runId + "/apply")
            .with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"mode\":\"OVERWRITE\",\"transactional\":true}"))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    assertThat(json.readTree(applyBody).get("status").asString()).isNotEqualTo("FAILED");
    JsonNode done = pollUntilTerminal(targetId, runId);
    assertThat(done.get("status").asString()).isEqualTo("DONE");

    List<Reference> targetRefs = references.findAllByProject((int) targetId);
    assertThat(targetRefs).hasSize(2); // original DOI ref + the new one
    Reference targetNewRefB = targetRefs.stream()
        .filter(r -> r.getAlternativeId() != null && r.getAlternativeId().contains("src:" + srcRefB.getId()))
        .findFirst().orElseThrow(() -> new AssertionError("new reference not found in target"));

    NameUsage targetUsage = usages.findAllByProject((int) targetId).stream()
        .filter(u -> "Refarrayus testus".equals(u.getScientificName()))
        .findFirst().orElseThrow(() -> new AssertionError("Refarrayus testus not found in target"));
    // Matched ref keeps its target id, new ref resolves to the freshly-inserted target row, the
    // unresolvable id is dropped entirely -- order preserved, nothing padded/nulled in its place.
    assertThat(targetUsage.getReferenceId()).containsExactly(targetRefA.getId(), targetNewRefB.getId());

    JsonNode issues = done.get("issues");
    assertThat(issues).isNotNull();
    boolean hasUnresolvedIssue = false;
    for (JsonNode issue : issues) {
      if (issue.get("message").asString().contains("referenceID " + unresolvableRefId + " not resolved")) {
        hasUnresolvedIssue = true;
      }
    }
    assertThat(hasUnresolvedIssue).as("unresolved referenceID issue").isTrue();
  }

  // Fix 3 (review): a pro-parte NEW synonym -- one source usage with TWO synonym_accepted links (to
  // two different accepted usages, one MATCHED and one NEW) -- must land with TWO target-side
  // synonym_accepted links, not just the first/last one processed, and parent_id stays null (the
  // status inverse, same as the single-link case in applyKeepsMatchedIdsAndClassifiesNewUsages).
  @Test
  @WithMockUser(username = "mergeApplyProParteOwner")
  void applyCreatesBothLinksForAProParteNewSynonym() throws Exception {
    ensureUser("mergeApplyProParteOwner");
    int userId = users.requireByUsernameOrNull("mergeApplyProParteOwner").getId();

    long targetId = createProject("mergeApplyProParteTarget");
    long sourceId = createProject("mergeApplyProParteSource");

    NameUsage targetGenus = newUsage(targetId, userId, "Proparte", null, "genus", Status.ACCEPTED, null, null);
    NameUsage targetLeo = newUsage(targetId, userId, "Proparte leo", "(Linnaeus, 1758)", "species",
        Status.ACCEPTED, targetGenus.getId(), null);

    NameUsage srcGenus = newUsage(sourceId, userId, "Proparte", null, "genus", Status.ACCEPTED, null, null);
    NameUsage srcLeo = newUsage(sourceId, userId, "Proparte leo", "(Linnaeus, 1758)", "species",
        Status.ACCEPTED, srcGenus.getId(), null);
    NameUsage srcOnca = newUsage(sourceId, userId, "Proparte onca", "Linnaeus, 1758", "species",
        Status.ACCEPTED, srcGenus.getId(), null);
    NameUsage srcSynonym = newUsage(sourceId, userId, "Proparte dubia", "Linnaeus, 1758", "species",
        Status.SYNONYM, null, null);
    synonymAccepted.link((int) sourceId, srcSynonym.getId(), srcLeo.getId(), 0);
    synonymAccepted.link((int) sourceId, srcSynonym.getId(), srcOnca.getId(), 1);

    String startBody = mvc.perform(post("/api/projects/" + targetId + "/merge")
            .with(csrf()).param("source", String.valueOf(sourceId)))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    long runId = json.readTree(startBody).get("id").asLong();
    JsonNode planned = pollUntilTerminal(targetId, runId);
    assertThat(planned.get("status").asString()).isEqualTo("PLANNED");

    String applyBody = mvc.perform(post("/api/projects/" + targetId + "/merge/" + runId + "/apply")
            .with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"mode\":\"OVERWRITE\",\"transactional\":true}"))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    assertThat(json.readTree(applyBody).get("status").asString()).isNotEqualTo("FAILED");
    JsonNode done = pollUntilTerminal(targetId, runId);
    assertThat(done.get("status").asString()).isEqualTo("DONE");

    List<NameUsage> targetUsages = usages.findAllByProject((int) targetId);
    NameUsage targetOnca = targetUsages.stream()
        .filter(u -> "Proparte onca".equals(u.getScientificName()))
        .findFirst().orElseThrow(() -> new AssertionError("Proparte onca not found in target"));
    NameUsage targetSynonym = targetUsages.stream()
        .filter(u -> "Proparte dubia".equals(u.getScientificName()))
        .findFirst().orElseThrow(() -> new AssertionError("Proparte dubia not found in target"));
    assertThat(targetSynonym.getParentId()).isNull();
    assertThat(targetSynonym.getStatus()).isEqualTo(Status.SYNONYM);

    List<Integer> acceptedOfSynonym =
        synonymAccepted.findAcceptedFor((int) targetId, targetSynonym.getId());
    assertThat(acceptedOfSynonym).containsExactlyInAnyOrder(targetLeo.getId(), targetOnca.getId());
  }
}
