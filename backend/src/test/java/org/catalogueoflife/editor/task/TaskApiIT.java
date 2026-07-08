package org.catalogueoflife.editor.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskApiIT extends AbstractPostgresIT {

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

  private JsonNode createTask(long pid, String title) throws Exception {
    String body = mvc.perform(post("/api/projects/" + pid + "/tasks").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"" + title + "\"}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body);
  }

  @Test
  @WithMockUser(username = "taskOwner")
  void createListFilterAndCloseTask() throws Exception {
    ensureUser("taskOwner");
    ensureUser("taskMember");
    ensureUser("taskOutsider");
    long pid = createProject("taskproj");
    AppUser owner = users.requireByUsernameOrNull("taskOwner");
    // a member with no elevated role -- can hold/create a task, but is neither its owner nor a
    // project owner, so must be refused when trying to close someone else's task.
    addMember(pid, "taskMember", "viewer");

    // 1) create -> 200, status "open", changeCount 0, owner is the creating actor.
    JsonNode created = createTask(pid, "Revise genus Abies");
    long taskId = created.get("id").asLong();
    assertThat(created.get("status").asString()).isEqualTo("open");
    assertThat(created.get("changeCount").asLong()).isEqualTo(0);
    assertThat(created.get("userId").asInt()).isEqualTo(owner.getId());
    assertThat(created.get("username").asString()).isEqualTo("taskOwner");
    assertThat(created.get("closedAt").isNull()).isTrue();

    // a second, still-open task so the status filters below have something to distinguish.
    JsonNode secondTask = createTask(pid, "Check synonyms");
    long secondTaskId = secondTask.get("id").asLong();

    // 2) list returns both, newest first.
    mvc.perform(get("/api/projects/" + pid + "/tasks"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].id").value(secondTaskId))
        .andExpect(jsonPath("$[1].id").value(taskId));

    // 3) close the first task -> status "closed", closed_at stamped.
    String closedBody = mvc.perform(patch("/api/projects/" + pid + "/tasks/" + taskId).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"closed\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("closed"))
        .andReturn().getResponse().getContentAsString();
    assertThat(json.readTree(closedBody).get("closedAt").isNull()).isFalse();

    // 4) ?status=open / ?status=closed filter correctly (case-insensitive).
    mvc.perform(get("/api/projects/" + pid + "/tasks").param("status", "open"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(secondTaskId));
    mvc.perform(get("/api/projects/" + pid + "/tasks").param("status", "CLOSED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(taskId));

    // 5) a member who is neither the task's owner nor a project owner cannot close it.
    mvc.perform(patch("/api/projects/" + pid + "/tasks/" + secondTaskId).with(csrf()).with(user("taskMember"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"closed\"}"))
        .andExpect(status().isForbidden());
    // ... and the task remains untouched (open) after the forbidden attempt.
    mvc.perform(get("/api/projects/" + pid + "/tasks/" + secondTaskId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("open"));

    // 6) a non-member gets 404 on the task list, not a leaked/empty result.
    mvc.perform(get("/api/projects/" + pid + "/tasks").with(user("taskOutsider")))
        .andExpect(status().isNotFound());

    // 7) a blank title is a 400, not a silently-created untitled task.
    mvc.perform(post("/api/projects/" + pid + "/tasks").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"  \"}"))
        .andExpect(status().isBadRequest());

    // 8) an unrecognized status value is a 400, both on create-time filtering and on update.
    mvc.perform(get("/api/projects/" + pid + "/tasks").param("status", "bogus"))
        .andExpect(status().isBadRequest());
    mvc.perform(patch("/api/projects/" + pid + "/tasks/" + secondTaskId).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"bogus\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(username = "taskProjectOwner")
  void projectOwnerMayCloseAnotherMembersTask() throws Exception {
    ensureUser("taskProjectOwner");
    ensureUser("taskEditor");
    long pid = createProject("taskproj2");
    addMember(pid, "taskEditor", "editor");

    // taskEditor (a non-owner member) creates and holds the task.
    String body = mvc.perform(post("/api/projects/" + pid + "/tasks").with(csrf()).with(user("taskEditor"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Editor's task\"}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    long taskId = json.readTree(body).get("id").asLong();

    // the project OWNER (not the task's own owner) may still close it.
    mvc.perform(patch("/api/projects/" + pid + "/tasks/" + taskId).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"closed\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("closed"));
  }
}
