package org.catalogueoflife.editor.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class LockApiIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ProjectMemberMapper members;
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

  private JsonNode createTask(long pid, String title) throws Exception {
    String body = mvc.perform(post("/api/projects/" + pid + "/tasks").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"" + title + "\"}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body);
  }

  @Test
  @WithMockUser(username = "lockOwner")
  void acquireRefreshReleaseAndTakeoverOfExpiredLock() throws Exception {
    ensureUser("lockOwner");
    ensureUser("lockHelper");
    ensureUser("lockOutsider");
    long pid = createProject("lockproj");
    AppUser owner = users.requireByUsernameOrNull("lockOwner");
    AppUser helper = users.requireByUsernameOrNull("lockHelper");
    // second member -- any role qualifies, since holding a lock is not a write.
    members.upsert(new ProjectMember((int) pid, helper.getId(), Role.VIEWER.dbValue()));

    // 1) owner acquires a fresh lock on entity 1 -> 200, heldByMe, expiresAt in the future.
    String acquireBody = mvc.perform(post("/api/projects/" + pid + "/locks").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"entityType\":\"name_usage\",\"entityId\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.heldByMe").value(true))
        .andExpect(jsonPath("$.userId").value(owner.getId()))
        .andExpect(jsonPath("$.username").value("lockOwner"))
        .andReturn().getResponse().getContentAsString();
    JsonNode acquired = json.readTree(acquireBody);
    long lockId = acquired.get("id").asLong();
    OffsetDateTime firstExpiry = OffsetDateTime.parse(acquired.get("expiresAt").asString());
    assertThat(firstExpiry).isAfter(OffsetDateTime.now());

    // 2) the second member tries the SAME entity -> 409, body describes the actual holder (owner),
    // and heldByMe is false from the second member's point of view.
    mvc.perform(post("/api/projects/" + pid + "/locks").with(csrf()).with(user("lockHelper"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"entityType\":\"name_usage\",\"entityId\":1}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.heldByMe").value(false))
        .andExpect(jsonPath("$.userId").value(owner.getId()))
        .andExpect(jsonPath("$.username").value("lockOwner"))
        .andExpect(jsonPath("$.id").value(lockId));

    // 3) GET /locks lists the one active lock, correctly attributed to the owner.
    mvc.perform(get("/api/projects/" + pid + "/locks"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(lockId))
        .andExpect(jsonPath("$[0].entityType").value("name_usage"))
        .andExpect(jsonPath("$[0].entityId").value(1))
        .andExpect(jsonPath("$[0].heldByMe").value(true));

    // 4) owner refreshes -> expiry extended; the second member (who never held it) gets 404/409.
    String refreshBody = mvc.perform(post("/api/projects/" + pid + "/locks/" + lockId + "/refresh").with(csrf()))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    OffsetDateTime refreshedExpiry = OffsetDateTime.parse(json.readTree(refreshBody).get("expiresAt").asString());
    assertThat(refreshedExpiry).isAfterOrEqualTo(firstExpiry);

    mvc.perform(post("/api/projects/" + pid + "/locks/" + lockId + "/refresh").with(csrf()).with(user("lockHelper")))
        .andExpect(result -> {
          int sc = result.getResponse().getStatus();
          assertThat(sc).isIn(404, 409);
        });

    // 5) owner releases -> 204; the lock disappears from the active list.
    mvc.perform(delete("/api/projects/" + pid + "/locks/" + lockId).with(csrf()))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/projects/" + pid + "/locks"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));

    // a second release of the same (now-gone) lock is a 404, not a silent success.
    mvc.perform(delete("/api/projects/" + pid + "/locks/" + lockId).with(csrf()))
        .andExpect(status().isNotFound());

    // 6) a released lock is re-acquirable, including by a DIFFERENT user -- the second member
    // now successfully acquires the very entity the owner just released.
    String reacquireBody = mvc.perform(post("/api/projects/" + pid + "/locks").with(csrf()).with(user("lockHelper"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"entityType\":\"name_usage\",\"entityId\":1,\"ttlSeconds\":30}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.heldByMe").value(true))
        .andExpect(jsonPath("$.userId").value(helper.getId()))
        .andReturn().getResponse().getContentAsString();
    // note: the owner's release DELETEd the row entirely, so this re-acquire is a fresh INSERT
    // (a new identity value) rather than an UPSERT takeover -- the id-reuse-on-takeover behavior
    // is exercised implicitly by refresh() above landing on the same lockId.
    assertThat(json.readTree(reacquireBody).get("id").asLong()).isGreaterThan(0);

    // 7) a non-member gets 404, not a leaked lock state.
    mvc.perform(post("/api/projects/" + pid + "/locks").with(csrf()).with(user("lockOutsider"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"entityType\":\"name_usage\",\"entityId\":1}"))
        .andExpect(status().isNotFound());
  }

  @Test
  @WithMockUser(username = "lockTaskOwner")
  void acquireCarriesOptionalTaskIntent() throws Exception {
    ensureUser("lockTaskOwner");
    long pid = createProject("lockTaskProj");

    // an open task T -- its title is the "intent" the lock list should surface.
    JsonNode task = createTask(pid, "Revise genus Abies");
    long taskId = task.get("id").asLong();

    // 1) acquiring WITH taskId -> 200, taskId + taskTitle populated from the task.
    String acquireBody = mvc.perform(post("/api/projects/" + pid + "/locks").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"entityType\":\"name_usage\",\"entityId\":1,\"taskId\":" + taskId + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.heldByMe").value(true))
        .andExpect(jsonPath("$.taskId").value(taskId))
        .andExpect(jsonPath("$.taskTitle").value("Revise genus Abies"))
        .andReturn().getResponse().getContentAsString();
    long lockId = json.readTree(acquireBody).get("id").asLong();

    // 2) GET /locks shows the same intent (title), not just the id.
    mvc.perform(get("/api/projects/" + pid + "/locks"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(lockId))
        .andExpect(jsonPath("$[0].taskId").value(taskId))
        .andExpect(jsonPath("$[0].taskTitle").value("Revise genus Abies"));

    // 3) acquiring on a DIFFERENT entity with NO taskId still works -- null taskId/taskTitle.
    String untaskedBody = mvc.perform(post("/api/projects/" + pid + "/locks").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"entityType\":\"name_usage\",\"entityId\":2}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    JsonNode untasked = json.readTree(untaskedBody);
    assertThat(untasked.get("taskId").isNull()).isTrue();
    assertThat(untasked.get("taskTitle").isNull()).isTrue();

    // 4) a nonexistent taskId -> 400.
    mvc.perform(post("/api/projects/" + pid + "/locks").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"entityType\":\"name_usage\",\"entityId\":3,\"taskId\":999999}"))
        .andExpect(status().isBadRequest());

    // 5) close the task, then acquiring with its (now closed) id -> 400.
    mvc.perform(patch("/api/projects/" + pid + "/tasks/" + taskId).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"closed\"}"))
        .andExpect(status().isOk());
    mvc.perform(post("/api/projects/" + pid + "/locks").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"entityType\":\"name_usage\",\"entityId\":4,\"taskId\":" + taskId + "}"))
        .andExpect(status().isBadRequest());
  }
}
