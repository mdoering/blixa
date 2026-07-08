package org.catalogueoflife.editor.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
import tools.jackson.databind.ObjectMapper;

// Exercises ValidationService.revalidateUsage's reconcile algorithm directly -- no HTTP validation
// endpoint exists yet (that's Task 2/3): data is seeded through the real project/usage/synonym-link
// API (per Global Constraints / Task 1 Step 4, "uses the real API to seed data"), the same way the
// write services actually populate name_usage/synonym_accepted, then ValidationService and
// IssueMapper are called/queried directly to assert the reconcile lifecycle.
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ValidationReconcileIT extends AbstractPostgresIT {

  private static final String RULE = "synonym_without_accepted";

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;
  @Autowired ValidationService validationService;
  @Autowired IssueMapper issueMapper;

  private void ensureUser(String username) {
    AppUser existing = users.requireByUsernameOrNull(username);
    if (existing == null) users.createLocal(username, "pw", username);
  }

  private long createProject(String title) throws Exception {
    String body = mvc.perform(post("/api/projects").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"" + title + "\",\"nomCode\":\"botanical\"}"))
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

  private Issue findIssue(int pid, int usageId) {
    return issueMapper.findByEntity(pid, "name_usage", usageId).stream()
        .filter(i -> RULE.equals(i.getRule()))
        .findFirst()
        .orElse(null);
  }

  // A comparable, order-independent snapshot of the fields the reconcile algorithm can touch, so
  // "run twice unchanged -> identical issue set" can be asserted without Issue needing equals().
  private static List<String> snapshot(List<Issue> issues) {
    return issues.stream()
        .map(i -> String.join("|", String.valueOf(i.getId()), i.getRule(), i.getSeverity(),
            i.getMessage(), i.getStatus(), String.valueOf(i.getReviewerId())))
        .sorted()
        .toList();
  }

  @Test
  @WithMockUser(username = "validationOwner")
  void synonymWithoutAcceptedReconcilesIdempotentlyThroughTheFullLifecycle() throws Exception {
    ensureUser("validationOwner");
    long pid = createProject("validationproj");
    int reviewerId = users.requireByUsernameOrNull("validationOwner").getId();

    long accId = createUsage(pid, "Reconcilus alpha", "L.", "species", "accepted");
    long synId = createUsage(pid, "Reconcilus beta", "Mill.", "species", "synonym");

    // 1) no accepted link yet -> revalidate inserts a fresh OPEN ERROR issue.
    validationService.revalidateUsage((int) pid, (int) synId);
    Issue issue = findIssue((int) pid, (int) synId);
    assertThat(issue).isNotNull();
    assertThat(issue.getStatus()).isEqualTo(IssueStatus.OPEN.name());
    assertThat(issue.getSeverity()).isEqualTo(Severity.ERROR.name());

    // 2) link to an accepted usage -> re-run -> the (OPEN) issue is deleted outright (cleared).
    mvc.perform(put("/api/projects/" + pid + "/usages/" + synId + "/synonym-of/" + accId).with(csrf()))
        .andExpect(status().isNoContent());
    validationService.revalidateUsage((int) pid, (int) synId);
    assertThat(findIssue((int) pid, (int) synId)).isNull();

    // 3) unlink again -> the problem re-appears -> re-run -> a fresh OPEN issue is (re)inserted.
    mvc.perform(delete("/api/projects/" + pid + "/usages/" + synId + "/synonym-of/" + accId).with(csrf()))
        .andExpect(status().isNoContent());
    validationService.revalidateUsage((int) pid, (int) synId);
    issue = findIssue((int) pid, (int) synId);
    assertThat(issue).isNotNull();
    assertThat(issue.getStatus()).isEqualTo(IssueStatus.OPEN.name());
    int issueId = issue.getId();

    // 4) a reviewer ACCEPTs it (directly via the mapper -- no review endpoint exists until Task 2).
    issueMapper.review(issueId, IssueStatus.ACCEPTED.name(), reviewerId);
    assertThat(findIssue((int) pid, (int) synId).getStatus()).isEqualTo(IssueStatus.ACCEPTED.name());

    // 5) clear the underlying problem again -> re-run -> an ACCEPTED issue whose finding clears
    // becomes DONE (completed work, kept as a record), not deleted.
    mvc.perform(put("/api/projects/" + pid + "/usages/" + synId + "/synonym-of/" + accId).with(csrf()))
        .andExpect(status().isNoContent());
    validationService.revalidateUsage((int) pid, (int) synId);
    Issue done = findIssue((int) pid, (int) synId);
    assertThat(done).isNotNull();
    assertThat(done.getId()).isEqualTo(issueId);
    assertThat(done.getStatus()).isEqualTo(IssueStatus.DONE.name());

    // 6) idempotence: re-running with unchanged data yields the IDENTICAL issue set -- e.g. this
    // usage also always trips missing_published_in (INFO, no published_in reference ever set), so
    // there are two issues here; the point is that a repeat run inserts nothing new and changes
    // nothing on either of them.
    List<Issue> beforeExtraRun = issueMapper.findByEntity((int) pid, "name_usage", (int) synId);
    validationService.revalidateUsage((int) pid, (int) synId);
    List<Issue> afterExtraRun = issueMapper.findByEntity((int) pid, "name_usage", (int) synId);
    assertThat(snapshot(afterExtraRun)).isEqualTo(snapshot(beforeExtraRun));
    Issue stillDone = afterExtraRun.stream().filter(i -> RULE.equals(i.getRule())).findFirst().orElseThrow();
    assertThat(stillDone.getId()).isEqualTo(issueId);
    assertThat(stillDone.getStatus()).isEqualTo(IssueStatus.DONE.name());
    assertThat(stillDone.getSeverity()).isEqualTo(done.getSeverity());
    assertThat(stillDone.getMessage()).isEqualTo(done.getMessage());

    // 7) a DONE issue whose finding recurs goes back to OPEN, and the stale reviewer is cleared
    // (Global Constraint: "a DONE finding that recurs -> back to OPEN").
    mvc.perform(delete("/api/projects/" + pid + "/usages/" + synId + "/synonym-of/" + accId).with(csrf()))
        .andExpect(status().isNoContent());
    validationService.revalidateUsage((int) pid, (int) synId);
    Issue reopened = findIssue((int) pid, (int) synId);
    assertThat(reopened.getId()).isEqualTo(issueId);
    assertThat(reopened.getStatus()).isEqualTo(IssueStatus.OPEN.name());
    assertThat(reopened.getReviewerId()).isNull();
  }
}
