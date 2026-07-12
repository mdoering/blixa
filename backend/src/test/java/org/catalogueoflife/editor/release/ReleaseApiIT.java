package org.catalogueoflife.editor.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.catalogueoflife.editor.project.ProjectMember;
import org.catalogueoflife.editor.project.ProjectMemberMapper;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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
  @Autowired ReleaseMapper releaseMapper;
  @Autowired ReleaseService releaseService;
  @Autowired @Qualifier(ReleaseAsyncConfig.EXECUTOR_BEAN) java.util.concurrent.Executor releaseExecutor;
  @Value("${coldp.release.dir:${java.io.tmpdir}/coldp-releases}") String releaseDirProp;

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

  // Reproduces the permanent-file-leak race: the release row is gone (owner deleted it) by the time
  // build() finishes, so ReleaseMapper.ready(...)'s CAS ("WHERE id = ? AND status = 'BUILDING'")
  // matches zero rows and silently no-ops. Before the fix, build() ignored that return value and left
  // {rid}.zip on disk forever with no DB reference and no retention sweep. We drive this deterministically
  // (no real race) by deleting the row *before* calling build() directly, then proving no orphan zip
  // survives -- ReleaseService.build() must now check ready()'s row count and delete the file it just wrote.
  @Test
  void orphanZipRemovedWhenReleaseDeletedBeforeBuildCompletes() throws Exception {
    int pid = project("relOwner");
    AppUser owner = users.requireByUsernameOrNull("relOwner");

    Release r = new Release();
    r.setProjectId(pid);
    r.setVersion("orphan-1.0");
    r.setCreatedBy(owner.getId());
    releaseMapper.insertBuilding(r);
    int rid = r.getId();

    // Simulate "owner deletes the release while the build is still running": the row is gone before
    // build() gets a chance to CAS it to READY.
    releaseMapper.delete(rid);

    // build() is @Async on ReleaseAsyncConfig.EXECUTOR_BEAN, a dedicated single-thread pool. Calling
    // it on the injected (proxied) bean still queues it onto that executor, so instead of polling we
    // submit a no-op barrier task right after it: since the pool is single-threaded and FIFO, waiting
    // on the barrier's Future guarantees build() has already run to completion.
    releaseService.build(pid, rid);
    Future<?> barrier = ((ThreadPoolTaskExecutor) releaseExecutor).getThreadPoolExecutor().submit(() -> { });
    barrier.get(10, TimeUnit.SECONDS);

    Path zip = Path.of(releaseDirProp).resolve(rid + ".zip");
    assertThat(Files.exists(zip)).isFalse();
  }
}
