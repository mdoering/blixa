package org.catalogueoflife.editor.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

// Exercises Task 2's HTTP surface end to end: seed a couple of real problems through the API
// (a synonym with no accepted link -> synonym_without_accepted ERROR; every usage created here
// also lacks published_in -> missing_published_in INFO, same as ValidationReconcileIT), trigger
// the on-demand POST /revalidate, then list/filter, review (reject/reopen), and summarize via the
// real controllers -- no direct ValidationService/IssueMapper calls, unlike Task 1's IT.
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IssueApiIT extends AbstractPostgresIT {

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

  private void addMember(long pid, String username, String role) throws Exception {
    mvc.perform(put("/api/projects/" + pid + "/members").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"role\":\"" + role + "\"}"))
        .andExpect(status().isOk());
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

  private JsonNode revalidate(long pid) throws Exception {
    String body = mvc.perform(post("/api/projects/" + pid + "/revalidate").with(csrf()))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body);
  }

  private JsonNode listIssues(long pid, String query) throws Exception {
    String body = mvc.perform(get("/api/projects/" + pid + "/issues" + query))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body);
  }

  @Test
  @WithMockUser(username = "issueOwner")
  void listFilterReviewLifecycleAndOnDemandRevalidate() throws Exception {
    ensureUser("issueOwner");
    ensureUser("issueViewer");
    ensureUser("issueOutsider");
    long pid = createProject("issueproj");
    int ownerId = users.requireByUsernameOrNull("issueOwner").getId();
    addMember(pid, "issueViewer", "viewer");

    // an accepted usage (only trips missing_published_in) and a synonym with no accepted link
    // (trips both missing_published_in and synonym_without_accepted ERROR).
    createUsage(pid, "Problemus alpha", "L.", "species", "accepted");
    long synId = createUsage(pid, "Problemus beta", "Mill.", "species", "synonym");

    // 1) on-demand revalidate finds all three issues (2x INFO missing_published_in + 1x ERROR
    // synonym_without_accepted) and returns the resulting summary.
    JsonNode summary = revalidate(pid);
    assertThat(summary.get("total").asLong()).isEqualTo(3);
    assertThat(summary.get("bySeverity").get("error").asLong()).isEqualTo(1);
    assertThat(summary.get("bySeverity").get("info").asLong()).isEqualTo(2);
    assertThat(summary.get("byStatus").get("open").asLong()).isEqualTo(3);

    // 2) GET /issues lists all three, most severe (error) first.
    JsonNode all = listIssues(pid, "");
    assertThat(all.size()).isEqualTo(3);
    assertThat(all.get(0).get("severity").asString()).isEqualTo("error");
    assertThat(all.get(0).get("entityType").asString()).isEqualTo("name_usage");

    // 3) ?severity=error filters down to the synonym_without_accepted issue on the synonym usage.
    JsonNode errorIssues = listIssues(pid, "?severity=error");
    assertThat(errorIssues.size()).isEqualTo(1);
    JsonNode errorIssue = errorIssues.get(0);
    assertThat(errorIssue.get("rule").asString()).isEqualTo("synonym_without_accepted");
    assertThat(errorIssue.get("entityId").asLong()).isEqualTo(synId);
    assertThat(errorIssue.get("status").asString()).isEqualTo("open");
    long errorIssueId = errorIssue.get("id").asLong();

    // 4) ?status=open matches all three (nothing reviewed yet).
    assertThat(listIssues(pid, "?status=open").size()).isEqualTo(3);

    // 5) reject the ERROR issue -> status "rejected", reviewer stamped, reviewedAt set.
    String rejectBody = mvc.perform(post("/api/projects/" + pid + "/issues/" + errorIssueId + "/review")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"action\":\"reject\"}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    JsonNode rejected = json.readTree(rejectBody);
    assertThat(rejected.get("status").asString()).isEqualTo("rejected");
    assertThat(rejected.get("reviewerId").asInt()).isEqualTo(ownerId);
    assertThat(rejected.get("reviewerUsername").asString()).isEqualTo("issueOwner");
    assertThat(rejected.get("reviewedAt").isNull()).isFalse();

    // 6) a second revalidate keeps it rejected -- the underlying finding (no accepted link) still
    // holds, so the reviewer decision survives the recompute.
    revalidate(pid);
    JsonNode stillRejected = listIssues(pid, "?status=rejected");
    assertThat(stillRejected.size()).isEqualTo(1);
    assertThat(stillRejected.get(0).get("id").asLong()).isEqualTo(errorIssueId);

    // 7) reopen -> back to "open" with the reviewer cleared.
    String reopenBody = mvc.perform(post("/api/projects/" + pid + "/issues/" + errorIssueId + "/review")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"action\":\"reopen\"}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    JsonNode reopened = json.readTree(reopenBody);
    assertThat(reopened.get("status").asString()).isEqualTo("open");
    assertThat(reopened.get("reviewerId").isNull()).isTrue();

    // 8) a viewer may read but not review -- 403, not a leaked write.
    mvc.perform(post("/api/projects/" + pid + "/issues/" + errorIssueId + "/review")
            .with(csrf()).with(user("issueViewer"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"action\":\"reject\"}"))
        .andExpect(status().isForbidden());

    // 9) a non-member gets 404 on the issue list, not a leaked/empty result.
    mvc.perform(get("/api/projects/" + pid + "/issues").with(user("issueOutsider")))
        .andExpect(status().isNotFound());

    // 10) summary reflects the final state: all three back to open.
    String finalSummaryBody = mvc.perform(get("/api/projects/" + pid + "/issues/summary"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    JsonNode finalSummary = json.readTree(finalSummaryBody);
    assertThat(finalSummary.get("total").asLong()).isEqualTo(3);
    assertThat(finalSummary.get("byStatus").get("open").asLong()).isEqualTo(3);
    assertThat(finalSummary.get("bySeverity").get("error").asLong()).isEqualTo(1);
    assertThat(finalSummary.get("bySeverity").get("info").asLong()).isEqualTo(2);

    // 11) an unrecognized filter value / review action is a 400, not a 500.
    mvc.perform(get("/api/projects/" + pid + "/issues").param("severity", "bogus"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/projects/" + pid + "/issues/" + errorIssueId + "/review").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"action\":\"bogus\"}"))
        .andExpect(status().isBadRequest());
  }
}
