package org.catalogueoflife.editor.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import org.catalogueoflife.editor.project.ProjectMember;
import org.catalogueoflife.editor.project.ProjectMemberMapper;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Task 2's end-to-end proof of the async release build: POST kicks off ReleaseService.publish
// (authorizes the owner, inserts the BUILDING release row synchronously, then fires the @Async
// build() through the self-proxy -- see ReleaseAsyncConfig/ReleaseService), returning 202
// immediately. Awaitility is NOT on this module's test classpath, so the terminal-status wait below
// is a bounded poll loop copied in spirit from ImportApiIT.pollUntilTerminal -- fixed interval, hard
// deadline, fails loudly with the last-seen list instead of hanging or racing the async job.
@AutoConfigureMockMvc
@WithMockUser(username = "relOwner")
class ReleaseApiIT extends AbstractPostgresIT {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ProjectMemberMapper members;
  @Autowired ObjectMapper json;

  private void ensureUser(String u) {
    if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u);
  }

  private int project(String owner) throws Exception {
    ensureUser(owner);
    String b = mvc.perform(post("/api/projects").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Rel\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    int pid = json.readTree(b).get("id").asInt();
    mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Panthera\",\"rank\":\"genus\",\"status\":\"ACCEPTED\"}"))
       .andExpect(status().isCreated());
    return pid;
  }

  private JsonNode listReleases(int pid) throws Exception {
    String body = mvc.perform(get("/api/projects/" + pid + "/releases"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body);
  }

  private JsonNode findRelease(JsonNode list, int rid) {
    for (JsonNode n : list) {
      if (n.get("id").asInt() == rid) {
        return n;
      }
    }
    return null;
  }

  // Bounded, deterministic wait for the release build to leave BUILDING -- polls the real GET list
  // endpoint on a short fixed interval up to TIMEOUT, mirroring ImportApiIT.pollUntilTerminal's
  // discipline (this project's substitute for Awaitility.await().untilAsserted(...)).
  private JsonNode pollUntilTerminal(int pid, int rid) throws Exception {
    Instant deadline = Instant.now().plus(TIMEOUT);
    JsonNode last;
    do {
      last = listReleases(pid);
      JsonNode row = findRelease(last, rid);
      if (row != null && !"BUILDING".equals(row.get("status").asString())) {
        return last;
      }
      Thread.sleep(POLL_INTERVAL.toMillis());
    } while (Instant.now().isBefore(deadline));
    throw new AssertionError("release " + rid + " did not finish within " + TIMEOUT + "; last GET = " + last);
  }

  @Test
  void ownerPublishesReleaseToReady() throws Exception {
    int pid = project("relOwner");
    String started = mvc.perform(post("/api/projects/" + pid + "/releases").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"1.0\",\"notes\":\"first\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("BUILDING"))
        .andReturn().getResponse().getContentAsString();
    int rid = json.readTree(started).get("id").asInt();

    JsonNode list = pollUntilTerminal(pid, rid);
    JsonNode row = findRelease(list, rid);
    assertThat(row).isNotNull();
    assertThat(row.get("status").asString()).isEqualTo("READY");
    assertThat(row.get("version").asString()).isEqualTo("1.0");
    assertThat(row.get("nameUsageCount").asInt()).isEqualTo(1);
    assertThat(row.get("fileName").asString()).isNotBlank();
  }

  @Test
  void nonOwnerCannotPublish() throws Exception {
    int pid = project("relOwner");
    ensureUser("relEditor");
    AppUser ed = users.requireByUsernameOrNull("relEditor");
    members.upsert(new ProjectMember(pid, ed.getId(), Role.EDITOR.dbValue()));
    mvc.perform(post("/api/projects/" + pid + "/releases").with(csrf()).with(user("relEditor"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"1.0\"}"))
       .andExpect(status().isForbidden());
  }

  @Test
  void ownerDeletesRelease() throws Exception {
    int pid = project("relOwner");
    String started = mvc.perform(post("/api/projects/" + pid + "/releases").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"1.0\"}"))
        .andReturn().getResponse().getContentAsString();
    int rid = json.readTree(started).get("id").asInt();
    JsonNode list = pollUntilTerminal(pid, rid);
    assertThat(findRelease(list, rid).get("status").asString()).isEqualTo("READY");

    mvc.perform(delete("/api/projects/" + pid + "/releases/" + rid).with(csrf()))
       .andExpect(status().isOk());
    mvc.perform(get("/api/projects/" + pid + "/releases"))
       .andExpect(jsonPath("$.length()").value(0));
  }
}
