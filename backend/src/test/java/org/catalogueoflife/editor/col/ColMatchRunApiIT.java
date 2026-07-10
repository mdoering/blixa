package org.catalogueoflife.editor.col;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import org.catalogueoflife.editor.name.ClbMatchClient;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Task 3's end-to-end proof of the async trigger + polling endpoints: POST kicks off
// ColMatchJobService.start (which fires the @Async run() through the self-proxy -- see
// ColMatchAsyncConfig/ColMatchJobService), returns 202 immediately with a RUNNING (or, if the tiny
// mocked job already raced ahead, DONE) row; GET polls the same row until it reaches a terminal
// state. No test-profile synchronous executor is introduced -- exactly like AutoRevalidateIT's
// bounded pollUntil loop against the real @Async listener, this polls the real single-thread
// ColMatchAsyncConfig.EXECUTOR_BEAN pool rather than faking synchronity.
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ColMatchRunApiIT extends AbstractPostgresIT {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

  @MockitoBean ClbMatchClient clb;

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

  private long createUsage(long pid, String name) throws Exception {
    String body = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"" + name + "\",\"rank\":\"species\",\"status\":\"accepted\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  private JsonNode getRun(long pid, long runId) throws Exception {
    String body = mvc.perform(get("/api/projects/" + pid + "/col-match/" + runId))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body);
  }

  // Bounded, deterministic wait for the async run to leave RUNNING -- same discipline as
  // AutoRevalidateIT.pollUntil: poll the real GET endpoint on a short fixed interval, fail loudly
  // with the last-seen row instead of hanging or racily asserting right after the POST.
  private JsonNode pollUntilTerminal(long pid, long runId) throws Exception {
    Instant deadline = Instant.now().plus(TIMEOUT);
    JsonNode last;
    do {
      last = getRun(pid, runId);
      if (!"RUNNING".equals(last.get("status").asString())) {
        return last;
      }
      Thread.sleep(POLL_INTERVAL.toMillis());
    } while (Instant.now().isBefore(deadline));
    throw new AssertionError("run did not finish within " + TIMEOUT + "; last GET = " + last);
  }

  private JsonNode listIssues(long pid) throws Exception {
    String body = mvc.perform(get("/api/projects/" + pid + "/issues"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body);
  }

  private static boolean hasRule(JsonNode issues, String rule) {
    for (JsonNode issue : issues) {
      if (rule.equals(issue.get("rule").asString())) {
        return true;
      }
    }
    return false;
  }

  @Test
  @WithMockUser(username = "colMatchRunOwner")
  void startsAndCompletesAProjectWideMatchRun() throws Exception {
    ensureUser("colMatchRunOwner");
    long pid = createProject("colmatchrunproj");
    createUsage(pid, "Panthera leo");
    createUsage(pid, "Panthera onca");

    when(clb.match(anyString(), any(), any(), any(), anyList()))
        .thenReturn(json.readTree("{\"type\":\"EXACT\",\"usage\":{\"id\":\"FIXEDCOL\"}}"));

    String startBody = mvc.perform(post("/api/projects/" + pid + "/col-match").with(csrf()))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    JsonNode started = json.readTree(startBody);
    assertThat(started.get("id").isNull()).isFalse();
    assertThat(started.get("status").asString()).isNotEqualTo("FAILED");
    long runId = started.get("id").asLong();

    JsonNode done = pollUntilTerminal(pid, runId);
    assertThat(done.get("status").asString()).isEqualTo("DONE");
    assertThat(done.get("total").asInt()).isEqualTo(2);
    assertThat(done.get("processed").asInt()).isEqualTo(done.get("total").asInt());
    assertThat(done.get("added").asInt()).isGreaterThanOrEqualTo(1);

    JsonNode issues = listIssues(pid);
    assertThat(hasRule(issues, "col_id_added")).isTrue();
  }

  @Test
  @WithMockUser(username = "colMatchRunNoMatchOwner")
  void unmatchedUsagesGetAColMatchMissingFlag() throws Exception {
    ensureUser("colMatchRunNoMatchOwner");
    long pid = createProject("colmatchrunnomatchproj");
    createUsage(pid, "Nonexistantus bogusii");

    when(clb.match(anyString(), any(), any(), any(), anyList()))
        .thenReturn(json.readTree("{\"type\":\"NONE\",\"usage\":null}"));

    String startBody = mvc.perform(post("/api/projects/" + pid + "/col-match").with(csrf()))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    long runId = json.readTree(startBody).get("id").asLong();

    JsonNode done = pollUntilTerminal(pid, runId);
    assertThat(done.get("status").asString()).isEqualTo("DONE");
    assertThat(done.get("unmatched").asInt()).isEqualTo(1);

    JsonNode issues = listIssues(pid);
    assertThat(hasRule(issues, "col_match_missing")).isTrue();
  }

  @Test
  @WithMockUser(username = "colMatchRunCrossProjOwner")
  void getReturns404WhenRunBelongsToAnotherProject() throws Exception {
    ensureUser("colMatchRunCrossProjOwner");
    long pid1 = createProject("colmatchruncrossproj1");
    long pid2 = createProject("colmatchruncrossproj2");

    when(clb.match(anyString(), any(), any(), any(), anyList()))
        .thenReturn(json.readTree("{\"type\":\"NONE\",\"usage\":null}"));

    String startBody = mvc.perform(post("/api/projects/" + pid1 + "/col-match").with(csrf()))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    long runId = json.readTree(startBody).get("id").asLong();

    mvc.perform(get("/api/projects/" + pid2 + "/col-match/" + runId))
        .andExpect(status().isNotFound());
  }

  @Test
  @WithMockUser(username = "colMatchRunPermOwner")
  void viewerCannotStartAMatchRunButOwnerCan() throws Exception {
    ensureUser("colMatchRunPermOwner");
    ensureUser("colMatchRunPermViewer");
    ensureUser("colMatchRunPermOutsider");
    long pid = createProject("colmatchrunpermproj");
    addMember(pid, "colMatchRunPermViewer", "viewer");

    // a viewer may not trigger the write-adjacent bulk job -- 403, not a leaked run.
    mvc.perform(post("/api/projects/" + pid + "/col-match").with(csrf()).with(user("colMatchRunPermViewer")))
        .andExpect(status().isForbidden());

    // a non-member gets 404, same as every other project-scoped endpoint.
    mvc.perform(post("/api/projects/" + pid + "/col-match").with(csrf()).with(user("colMatchRunPermOutsider")))
        .andExpect(status().isNotFound());
  }
}
