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
import org.catalogueoflife.editor.parse.NameParserService;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.catalogueoflife.editor.validation.Issue;
import org.catalogueoflife.editor.validation.IssueMapper;
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

// Task 7's end-to-end proof of the full-import fast path: merging a source project into a
// completely EMPTY target has NO MATCHED/POSSIBLE_* candidate at all (every reference and every
// name-usage is NEW), so MergeApplyService.apply forces transactional=false regardless of the
// request body (this test doesn't send `transactional` at all -- the omitted/null request default
// would otherwise resolve to true) -- see MergeApplyService.isFullImport/apply's javadoc. Also
// asserts the batched non-transactional path reproduces the full classification + reference remap
// exactly like the transactional path (MergeApplyNamesIT covers that path in detail already), and
// that the best-effort post-commit validationService.revalidateProject actually ran: the source's
// orphaned synonym ("Felis leo", SYNONYM, no synonym_accepted link at all) is deliberately
// constructed so that once the target has been populated, SynonymWithoutAcceptedRule immediately
// fires an OPEN "synonym_without_accepted" issue against it -- a finding that can ONLY exist if
// revalidateProject actually ran (nothing else in the apply path itself ever writes to `issue`).
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MergeApplyFullImportIT extends AbstractPostgresIT {

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
  @Autowired IssueMapper issues;

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

  private Reference newReference(long projectId, int userId, String citation) {
    Reference r = new Reference();
    r.setProjectId((int) projectId);
    r.setCitation(citation);
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

  // runs.finish (DONE) commits BEFORE the async worker's best-effort revalidateProject call even
  // starts (see MergeApplyService.run's javadoc -- same "finish first, then best-effort revalidate"
  // shape as ImportRunService.run) -- both on the SAME single-thread executor, so revalidateProject
  // is guaranteed to eventually run once the executor reaches it, but a poll loop that only waits
  // for status==DONE can race ahead of it. Poll for the expected issue directly instead.
  private Issue pollForIssue(int targetId, int usageId, String rule) throws Exception {
    Instant deadline = Instant.now().plus(TIMEOUT);
    do {
      List<Issue> found = issues.findByEntity(targetId, ENTITY, usageId);
      for (Issue i : found) {
        if (rule.equals(i.getRule())) {
          return i;
        }
      }
      Thread.sleep(POLL_INTERVAL.toMillis());
    } while (Instant.now().isBefore(deadline));
    throw new AssertionError("issue rule=" + rule + " never appeared on usage " + usageId + " within " + TIMEOUT);
  }

  @Test
  @WithMockUser(username = "mergeFullImportOwner")
  void fullImportIntoEmptyTargetDefaultsNonTransactionalAndRevalidates() throws Exception {
    ensureUser("mergeFullImportOwner");
    int userId = users.requireByUsernameOrNull("mergeFullImportOwner").getId();

    long targetId = createProject("fullImportEmptyTarget");
    long sourceId = createProject("fullImportSource");

    Reference srcRef = newReference(sourceId, userId, "Doe, J. 2020. A full-import reference. Journal, 1, 2-3.");
    NameUsage srcGenus = newUsage(sourceId, userId, "Panthera", null, "genus", Status.ACCEPTED, null, null);
    NameUsage srcLeo = newUsage(sourceId, userId, "Panthera leo", "(Linnaeus, 1758)", "species",
        Status.ACCEPTED, srcGenus.getId(), srcRef.getId());
    // Deliberately orphaned: no synonym_accepted link at all -- the revalidation proof.
    newUsage(sourceId, userId, "Felis leo", "Linnaeus, 1758", "species",
        Status.SYNONYM, null, null);

    // Compute-plan -> PLANNED: an empty target means every candidate is NEW.
    String startBody = mvc.perform(post("/api/projects/" + targetId + "/merge")
            .with(csrf()).param("source", String.valueOf(sourceId)))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    long runId = json.readTree(startBody).get("id").asLong();
    JsonNode planned = pollUntilTerminal(targetId, runId);
    assertThat(planned.get("status").asString()).isEqualTo("PLANNED");
    assertThat(planned.get("metrics").get("names").get("matched").asInt()).isEqualTo(0);
    assertThat(planned.get("metrics").get("names").get("new").asInt()).isEqualTo(3);
    assertThat(planned.get("metrics").get("references").get("matched").asInt()).isEqualTo(0);
    assertThat(planned.get("metrics").get("references").get("new").asInt()).isEqualTo(1);

    // Apply: `transactional` is deliberately OMITTED from the request body -- the full-import fast
    // path must override the (otherwise-true) default to false on its own.
    String applyBody = mvc.perform(post("/api/projects/" + targetId + "/merge/" + runId + "/apply")
            .with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"mode\":\"OVERWRITE\"}"))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    JsonNode acceptedRun = json.readTree(applyBody);
    assertThat(acceptedRun.get("status").asString()).isNotEqualTo("FAILED");
    // startApply persists the RESOLVED flag synchronously before the 202 is even returned (see
    // MergeApplyService.apply) -- so this is already observable on the very first response.
    assertThat(acceptedRun.get("transactional").asBoolean()).isFalse();

    JsonNode done = pollUntilTerminal(targetId, runId);
    assertThat(done.get("status").asString()).isEqualTo("DONE");
    assertThat(done.get("transactional").asBoolean()).isFalse();

    // Full classification reproduced.
    List<NameUsage> targetUsages = usages.findAllByProject((int) targetId);
    assertThat(targetUsages).hasSize(3);
    NameUsage targetGenus = targetUsages.stream()
        .filter(u -> "Panthera".equals(u.getScientificName())).findFirst()
        .orElseThrow(() -> new AssertionError("Panthera not found in target"));
    NameUsage targetLeo = targetUsages.stream()
        .filter(u -> "Panthera leo".equals(u.getScientificName())).findFirst()
        .orElseThrow(() -> new AssertionError("Panthera leo not found in target"));
    NameUsage targetFelisLeo = targetUsages.stream()
        .filter(u -> "Felis leo".equals(u.getScientificName())).findFirst()
        .orElseThrow(() -> new AssertionError("Felis leo not found in target"));
    assertThat(targetGenus.getParentId()).isNull();
    assertThat(targetLeo.getParentId()).isEqualTo(targetGenus.getId());
    assertThat(targetLeo.getAlternativeId()).contains("src:" + srcLeo.getId());

    // References added, and the remap resolves to the NEW target reference (never the source's own
    // reference id, which is meaningless in the target project).
    List<Reference> targetRefs = references.findAllByProject((int) targetId);
    assertThat(targetRefs).hasSize(1);
    assertThat(targetRefs.get(0).getAlternativeId()).contains("src:" + srcRef.getId());
    assertThat(targetLeo.getPublishedInReferenceId()).isEqualTo(targetRefs.get(0).getId());

    // Validation ran: the orphaned synonym immediately gets an OPEN synonym_without_accepted issue.
    Issue issue = pollForIssue((int) targetId, targetFelisLeo.getId(), "synonym_without_accepted");
    assertThat(issue.getStatus()).isEqualTo("OPEN");
  }
}
