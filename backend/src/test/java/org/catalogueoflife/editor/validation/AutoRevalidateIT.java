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
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
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

// Task 3's end-to-end proof: NO manual ValidationService/IssueMapper/POST-revalidate call anywhere
// in this test. Creating a synonym with no accepted link publishes a ValidationEvent from inside
// NameUsageService.create's own @Transactional method (see NameUsageService); ValidationTrigger's
// @TransactionalEventListener(AFTER_COMMIT) + @Async picks it up, off the request thread, once that
// transaction has actually committed -- by the time the MockMvc POST above returns 201, the
// revalidation may not have run yet. So every assertion here goes through pollUntil: a bounded,
// deterministic retry loop over the real GET /issues endpoint, never a synchronous assert right
// after the write (that would be racy against the async listener by construction).
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AutoRevalidateIT extends AbstractPostgresIT {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

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

  private long createUsage(long pid, String name, String authorship, String rank, String statusValue)
      throws Exception {
    String body = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"" + name + "\",\"authorship\":\"" + authorship
                + "\",\"rank\":\"" + rank + "\",\"status\":\"" + statusValue + "\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  private JsonNode listIssues(long pid) throws Exception {
    String body = mvc.perform(get("/api/projects/" + pid + "/issues"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body);
  }

  private long createReference(long pid, String citation, String issued) throws Exception {
    String body = mvc.perform(post("/api/projects/" + pid + "/references").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"citation\":\"" + citation + "\",\"issued\":\"" + issued + "\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  private long createUsageCitingReference(long pid, String name, String authorship, String rank,
      String statusValue, long refId, int year) throws Exception {
    String body = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"" + name + "\",\"authorship\":\"" + authorship
                + "\",\"rank\":\"" + rank + "\",\"status\":\"" + statusValue
                + "\",\"publishedInReferenceId\":" + refId + ",\"publishedInYear\":" + year + "}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  private static boolean hasIssue(JsonNode issues, long entityId, String rule, String severity) {
    for (JsonNode issue : issues) {
      if (issue.get("entityId").asLong() == entityId
          && rule.equals(issue.get("rule").asString())
          && severity.equals(issue.get("severity").asString())) {
        return true;
      }
    }
    return false;
  }

  // Bounded, deterministic wait for the async listener: polls GET /issues on a short fixed
  // interval up to TIMEOUT, failing loudly (with the last-seen issue list) rather than hanging or
  // silently passing on a stale read.
  private JsonNode pollUntil(long pid, Predicate<JsonNode> condition) throws Exception {
    Instant deadline = Instant.now().plus(TIMEOUT);
    JsonNode last;
    do {
      last = listIssues(pid);
      if (condition.test(last)) {
        return last;
      }
      Thread.sleep(POLL_INTERVAL.toMillis());
    } while (Instant.now().isBefore(deadline));
    throw new AssertionError("condition not met within " + TIMEOUT + "; last GET /issues = " + last);
  }

  @Test
  @WithMockUser(username = "autoRevalidateOwner")
  void autoRevalidatesAfterEachWriteWithoutAManualTrigger() throws Exception {
    ensureUser("autoRevalidateOwner");
    long pid = createProject("autorevalidateproj");

    long accId = createUsage(pid, "Autorevalidus alpha", "L.", "species", "accepted");
    long synId = createUsage(pid, "Autorevalidus beta", "Mill.", "species", "synonym");

    // 1) no accepted link yet -> the create's ValidationEvent should, asynchronously and after
    // commit, produce an OPEN synonym_without_accepted ERROR -- with no revalidate call anywhere.
    JsonNode withError = pollUntil(pid, issues -> hasIssue(issues, synId, "synonym_without_accepted", "error"));
    assertThat(hasIssue(withError, synId, "synonym_without_accepted", "error")).isTrue();

    // 2) link it to an accepted usage via the real API -- linkSynonym also publishes a
    // ValidationEvent for the synonym (see NameUsageService.linkSynonym) -- and poll until the
    // auto-revalidate clears the ERROR (no revalidate call here either).
    mvc.perform(put("/api/projects/" + pid + "/usages/" + synId + "/synonym-of/" + accId).with(csrf()))
        .andExpect(status().isNoContent());
    JsonNode cleared = pollUntil(pid, issues -> !hasIssue(issues, synId, "synonym_without_accepted", "error"));
    assertThat(hasIssue(cleared, synId, "synonym_without_accepted", "error")).isFalse();
  }

  // Fix 3: deleting a reference nulls every citing usage's published_in_reference_id via
  // ON DELETE SET NULL (see ReferenceService.delete / V3__name_core.sql) -- which should clear
  // year_vs_reference and trip missing_published_in -- but ONLY update() published a ValidationEvent
  // before this fix, so a deleted reference's citing usages were never re-checked. No manual
  // revalidate call anywhere here: everything must flow from the real create/delete API calls plus
  // the async auto-revalidate listener, same discipline as autoRevalidatesAfterEachWriteWithoutAManualTrigger.
  @Test
  @WithMockUser(username = "autoRevalidateRefDeleteOwner")
  void deletingAReferenceRevalidatesItsFormerlyCitingUsages() throws Exception {
    ensureUser("autoRevalidateRefDeleteOwner");
    long pid = createProject("autorevalidaterefdeleteproj");

    // reference "issued" year (1700) differs from the usage's publishedInYear (2000) by way more
    // than YearVsReferenceRule's MAX_DIFF of 2 -- so citing this reference trips year_vs_reference.
    long refId = createReference(pid, "Old 1700, Some Work", "1700");
    long usageId = createUsageCitingReference(pid, "Autorevaliddeletus alpha", "L.", "species",
        "accepted", refId, 2000);

    // 1) the create's ValidationEvent should, asynchronously, produce the year_vs_reference WARNING
    // (and, since publishedInReferenceId IS set, no missing_published_in INFO yet).
    JsonNode withWarning = pollUntil(pid, issues -> hasIssue(issues, usageId, "year_vs_reference", "warning"));
    assertThat(hasIssue(withWarning, usageId, "year_vs_reference", "warning")).isTrue();
    assertThat(hasIssue(withWarning, usageId, "missing_published_in", "info")).isFalse();

    // 2) delete the reference via the real API -- ON DELETE SET NULL clears the usage's
    // published_in_reference_id, and ReferenceService.delete must publish a ValidationEvent for the
    // usage (captured BEFORE the delete, since querying by refId afterwards finds nothing).
    mvc.perform(delete("/api/projects/" + pid + "/references/" + refId).with(csrf()))
        .andExpect(status().isNoContent());

    // 3) poll until the async re-revalidate reflects the change: year_vs_reference gone (no
    // reference left to compare against) and missing_published_in now fires (publishedInReferenceId
    // is null).
    JsonNode after = pollUntil(pid, issues -> !hasIssue(issues, usageId, "year_vs_reference", "warning")
        && hasIssue(issues, usageId, "missing_published_in", "info"));
    assertThat(hasIssue(after, usageId, "year_vs_reference", "warning")).isFalse();
    assertThat(hasIssue(after, usageId, "missing_published_in", "info")).isTrue();
  }
}
