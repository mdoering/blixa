package org.catalogueoflife.editor.publicapi;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.test.web.servlet.MockMvc;

// Task 7: anonymous public release download -- streams the persisted release zip for a public
// project's READY release only. Awaitility is NOT on this module's test classpath, so the
// terminal-status wait is the same bounded poll loop as ReleaseApiIT.pollUntilTerminal /
// ImportApiIT.pollUntilTerminal: fixed interval, hard deadline, fails loudly instead of hanging.
@AutoConfigureMockMvc
class PublicReleaseDownloadIT extends AbstractPostgresIT {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

  private void ensureUser(String u) {
    if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u);
  }

  private int createProject(String owner, String title) throws Exception {
    ensureUser(owner);
    String b = mvc.perform(post("/api/projects").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + title + "\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    int pid = json.readTree(b).get("id").asInt();
    mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Panthera\",\"rank\":\"genus\",\"status\":\"ACCEPTED\"}"))
       .andExpect(status().isCreated());
    return pid;
  }

  private void setPublic(String owner, int pid, boolean pub) throws Exception {
    mvc.perform(put("/api/projects/" + pid + "/public").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON).content("{\"public\":" + pub + "}"))
       .andExpect(status().isOk());
  }

  private int publishRelease(String owner, int pid) throws Exception {
    String started = mvc.perform(post("/api/projects/" + pid + "/releases").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"1.0\"}"))
        .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
    return json.readTree(started).get("id").asInt();
  }

  // Bounded, deterministic wait for the release build to leave BUILDING -- same discipline as
  // ReleaseApiIT.pollUntilTerminal (Awaitility is not available on this module's test classpath).
  private String pollUntilTerminal(String owner, int pid, int rid) throws Exception {
    Instant deadline = Instant.now().plus(TIMEOUT);
    String status = null;
    do {
      String body = mvc.perform(get("/api/projects/" + pid + "/releases").with(user(owner)))
          .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
      JsonNode list = json.readTree(body);
      for (JsonNode n : list) {
        if (n.get("id").asInt() == rid) {
          status = n.get("status").asString();
        }
      }
      if (status != null && !"BUILDING".equals(status)) {
        return status;
      }
      Thread.sleep(POLL_INTERVAL.toMillis());
    } while (Instant.now().isBefore(deadline));
    throw new AssertionError("release " + rid + " did not finish within " + TIMEOUT + "; last status = " + status);
  }

  @Test
  void publicReadyReleaseDownloads() throws Exception {
    String owner = "dlOwner";
    int pid = createProject(owner, "Downloadable");
    setPublic(owner, pid, true);
    int rid = publishRelease(owner, pid);
    String finalStatus = pollUntilTerminal(owner, pid, rid);
    org.assertj.core.api.Assertions.assertThat(finalStatus).isEqualTo("READY");

    // Anonymous request -- no @WithMockUser, no user() postprocessor.
    mvc.perform(get("/api/public/projects/" + pid + "/releases/" + rid + "/download"))
       .andExpect(status().isOk())
       .andExpect(header().string("Content-Disposition", containsString("attachment")));
  }

  @Test
  void privateProjectReleaseDownloadIsNotFound() throws Exception {
    String owner = "dlOwner2";
    int pid = createProject(owner, "Private Download");
    // Not made public.
    int rid = publishRelease(owner, pid);
    String finalStatus = pollUntilTerminal(owner, pid, rid);
    org.assertj.core.api.Assertions.assertThat(finalStatus).isEqualTo("READY");

    mvc.perform(get("/api/public/projects/" + pid + "/releases/" + rid + "/download"))
       .andExpect(status().isNotFound());
  }
}
