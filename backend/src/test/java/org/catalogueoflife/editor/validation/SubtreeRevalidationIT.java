package org.catalogueoflife.editor.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Predicate;
import org.catalogueoflife.editor.lock.Lock;
import org.catalogueoflife.editor.lock.LockMapper;
import org.catalogueoflife.editor.lock.LockRetentionSweep;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// End-to-end (real Postgres) coverage for subtree (group) revalidation
// (docs/superpowers/specs/2026-07-24-subtree-revalidation-design.md). The pivot case throughout is a
// RELATIONAL rule the per-usage auto-trigger misses: genus_year_after_species fires on a species when
// its ancestor genus's year is later than the species' own. Editing the genus reschedules only the
// genus (NameUsageService.update -> ValidationEvent.forUsage(genus)), never the species -- so the
// species' finding goes stale until something revalidates the subtree. That "something" is what this
// tests: the manual POST /usages/{id}/revalidate, and the automatic release/sweep of an
// objective-tagged lock. Async paths use pollUntil (same discipline as AutoRevalidateIT).
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "subtreeOwner")
class SubtreeRevalidationIT extends AbstractPostgresIT {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
  private static final String GENUS_YEAR_RULE = "genus_year_after_species";

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;
  @Autowired LockMapper locks;
  @Autowired LockRetentionSweep sweep;

  private void ensureUser(String u) {
    if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u);
  }

  private long createProject(String title) throws Exception {
    String body = mvc.perform(post("/api/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"" + title + "\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  private long createUsage(long pid, String name, String rank, Long parentId, Integer year) throws Exception {
    StringBuilder c = new StringBuilder("{\"scientificName\":\"" + name + "\",\"rank\":\"" + rank
        + "\",\"status\":\"accepted\"");
    if (parentId != null) c.append(",\"parentId\":").append(parentId);
    if (year != null) c.append(",\"publishedInYear\":").append(year);
    c.append("}");
    String body = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(c.toString()))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  // Push the genus's published year later than its species' -- the edit that makes the species' finding
  // stale. Full-replace PUT with version 0 (the genus has had no write since create).
  private void setGenusYear(long pid, long genusId, String name, int year) throws Exception {
    mvc.perform(put("/api/projects/" + pid + "/usages/" + genusId).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"" + name + "\",\"rank\":\"genus\",\"status\":\"accepted\""
                + ",\"publishedInYear\":" + year + ",\"version\":0}"))
        .andExpect(status().isOk());
  }

  private long createObjective(long pid, String title) throws Exception {
    String body = mvc.perform(post("/api/projects/" + pid + "/discussions").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + title + "\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  private long acquireLock(long pid, long usageId, Long objectiveId) throws Exception {
    String obj = objectiveId == null ? "" : ",\"discussionId\":" + objectiveId;
    String body = mvc.perform(post("/api/projects/" + pid + "/locks").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"entityType\":\"name_usage\",\"entityId\":" + usageId + obj + "}"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  private JsonNode listIssues(long pid) throws Exception {
    return json.readTree(mvc.perform(get("/api/projects/" + pid + "/issues"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  }

  private static boolean hasRule(JsonNode issues, long entityId, String rule) {
    for (JsonNode i : issues) {
      if (i.get("entityId").asLong() == entityId && rule.equals(i.get("rule").asString())) return true;
    }
    return false;
  }

  private JsonNode pollUntil(long pid, Predicate<JsonNode> cond) throws Exception {
    Instant deadline = Instant.now().plus(TIMEOUT);
    JsonNode last;
    do {
      last = listIssues(pid);
      if (cond.test(last)) return last;
      Thread.sleep(POLL_INTERVAL.toMillis());
    } while (Instant.now().isBefore(deadline));
    throw new AssertionError("condition not met within " + TIMEOUT + "; last GET /issues = " + last);
  }

  // Create a synonym with no accepted link -> trips synonym_without_accepted (ERROR). Used as an
  // out-of-subtree usage to prove the subtree-scoped summary excludes it.
  private long createOrphanSynonym(long pid, String name) throws Exception {
    String body = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"" + name + "\",\"rank\":\"species\",\"status\":\"synonym\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  @Test
  void manualRevalidateRecomputesTheSubtreeAndReturnsAScopedSummary() throws Exception {
    ensureUser("subtreeOwner");
    long pid = createProject("manualSubtree");
    long genus = createUsage(pid, "Ausius", "genus", null, 1800);
    long species = createUsage(pid, "Ausius busius", "species", genus, 1900);

    // An ERROR OUTSIDE the genus subtree; wait until the create's async trigger records it, so we
    // know it's real before asserting the subtree-scoped summary leaves it out.
    long outsider = createOrphanSynonym(pid, "Outsideus alienus");
    pollUntil(pid, issues -> hasRule(issues, outsider, "synonym_without_accepted"));

    // Genus now younger than the species it contains -> the species SHOULD trip genus_year_after_species,
    // but updating the genus reschedules only the genus, so the species finding is still absent.
    setGenusYear(pid, genus, "Ausius", 2000);
    assertThat(hasRule(listIssues(pid), species, GENUS_YEAR_RULE)).isFalse();

    // Manual subtree revalidate rooted at the genus: synchronous, returns a subtree-scoped summary.
    String summaryBody = mvc.perform(post("/api/projects/" + pid + "/usages/" + genus + "/revalidate").with(csrf()))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    JsonNode summary = json.readTree(summaryBody);

    // The stale descendant is now flagged...
    assertThat(hasRule(listIssues(pid), species, GENUS_YEAR_RULE)).isTrue();
    // ...and the summary is scoped to the subtree: it carries the subtree's own INFO findings but NOT
    // the out-of-subtree synonym's ERROR (which a project-wide summary would include).
    assertThat(summary.get("total").asLong()).isGreaterThanOrEqualTo(1);
    long infoCount = summary.get("bySeverity").has("info") ? summary.get("bySeverity").get("info").asLong() : 0;
    assertThat(infoCount).isGreaterThanOrEqualTo(1);
    long errorCount = summary.get("bySeverity").has("error") ? summary.get("bySeverity").get("error").asLong() : 0;
    assertThat(errorCount).isZero();
  }

  @Test
  void releasingAnObjectiveTaggedLockRevalidatesItsSubtree() throws Exception {
    ensureUser("subtreeOwner");
    long pid = createProject("releaseSubtree");
    long genus = createUsage(pid, "Ausius", "genus", null, 1800);
    long species = createUsage(pid, "Ausius busius", "species", genus, 1900);
    long objective = createObjective(pid, "Revise Ausius");

    long lockId = acquireLock(pid, genus, objective);
    setGenusYear(pid, genus, "Ausius", 2000);
    assertThat(hasRule(listIssues(pid), species, GENUS_YEAR_RULE)).isFalse();

    // Releasing the objective-tagged lock triggers an async subtree revalidate.
    mvc.perform(delete("/api/projects/" + pid + "/locks/" + lockId).with(csrf()))
        .andExpect(status().isNoContent());
    JsonNode after = pollUntil(pid, issues -> hasRule(issues, species, GENUS_YEAR_RULE));
    assertThat(hasRule(after, species, GENUS_YEAR_RULE)).isTrue();
  }

  @Test
  void releasingAnUntaggedLockDoesNotRevalidateTheSubtree() throws Exception {
    ensureUser("subtreeOwner");
    long pid = createProject("untaggedRelease");
    long genus = createUsage(pid, "Ausius", "genus", null, 1800);
    long species = createUsage(pid, "Ausius busius", "species", genus, 1900);

    long lockId = acquireLock(pid, genus, null); // no objective
    setGenusYear(pid, genus, "Ausius", 2000);

    mvc.perform(delete("/api/projects/" + pid + "/locks/" + lockId).with(csrf()))
        .andExpect(status().isNoContent());
    // An ungrouped lock must NOT trigger a subtree sweep -- the species finding stays stale. Give the
    // async pool a bounded window to (not) act, then assert absence.
    Thread.sleep(1000);
    assertThat(hasRule(listIssues(pid), species, GENUS_YEAR_RULE)).isFalse();
  }

  @Test
  void sweepingAnExpiredObjectiveTaggedLockRevalidatesItsSubtree() throws Exception {
    ensureUser("subtreeOwner");
    long pid = createProject("sweepSubtree");
    long genus = createUsage(pid, "Ausius", "genus", null, 1800);
    long species = createUsage(pid, "Ausius busius", "species", genus, 1900);
    long objective = createObjective(pid, "Revise Ausius");

    long lockId = acquireLock(pid, genus, objective);
    setGenusYear(pid, genus, "Ausius", 2000);
    assertThat(hasRule(listIssues(pid), species, GENUS_YEAR_RULE)).isFalse();

    // Force the lock into the expired state and run the sweep directly (the @Scheduled cadence is
    // irrelevant to the query under test). The sweep captures the objective-tagged root before
    // deleting and publishes a SubtreeValidationEvent, revalidated async.
    Lock lock = locks.findById((int) pid, (int) lockId);
    locks.expireForTest(lock.getId());
    sweep.sweep();
    JsonNode after = pollUntil(pid, issues -> hasRule(issues, species, GENUS_YEAR_RULE));
    assertThat(hasRule(after, species, GENUS_YEAR_RULE)).isTrue();
  }
}
