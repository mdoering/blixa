package org.catalogueoflife.editor.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.project.ProjectMember;
import org.catalogueoflife.editor.project.ProjectMemberMapper;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeDashboardIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired AppUserMapper userMapper;
  @Autowired ProjectMemberMapper members;
  @Autowired ObjectMapper json;

  private void ensureUser(String username) {
    if (users.requireByUsernameOrNull(username) == null) users.createLocal(username, "pw", username);
  }

  private long createProject(String title, String actor) throws Exception {
    String body = mvc.perform(post("/api/projects").with(csrf()).with(user(actor))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"" + title + "\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  private long createUsage(long pid, String name, String statusValue, String actor) throws Exception {
    String body = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf()).with(user(actor))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"" + name + "\",\"rank\":\"species\",\"status\":\""
                + statusValue + "\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  private JsonNode dashboardAs(String actor) throws Exception {
    String body = mvc.perform(get("/api/me/dashboard").with(user(actor)))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    return json.readTree(body);
  }

  private static JsonNode findByTitle(JsonNode array, String title) {
    for (JsonNode n : array) {
      if (title.equals(n.path("title").asString(null)) || title.equals(n.path("projectTitle").asString(null))) {
        return n;
      }
    }
    return null;
  }

  @Test
  void aggregatesProjectsHeadlineCountsMissingMetadataAndRecent() throws Exception {
    ensureUser("dashOwner");
    ensureUser("dashOther");
    long a = createProject("dashA", "dashOwner");
    createUsage(a, "Aus aaa", "accepted", "dashOwner");
    createUsage(a, "Bus bbb", "accepted", "dashOwner");
    createUsage(a, "Cus ccc", "synonym", "dashOwner");
    // a project the owner is NOT a member of -- must not leak into their dashboard.
    createProject("dashForeign", "dashOther");

    JsonNode dash = dashboardAs("dashOwner");

    JsonNode cardA = findByTitle(dash.get("projects"), "dashA");
    assertThat(cardA).isNotNull();
    assertThat(cardA.get("role").asString()).isEqualTo("owner");
    assertThat(cardA.get("accepted").asLong()).isEqualTo(2);
    assertThat(cardA.get("synonyms").asLong()).isEqualTo(1);
    assertThat(findByTitle(dash.get("projects"), "dashForeign")).isNull();

    // fresh project has a nomCode but no license -> missing-metadata card lists it.
    JsonNode miss = findByTitle(dash.get("missingMetadata"), "dashA");
    assertThat(miss).isNotNull();
    assertThat(miss.get("missing").toString()).contains("license");

    // recently edited by me: the three usages I just created.
    JsonNode recent = dash.get("recentTaxa");
    assertThat(recent.size()).isEqualTo(3);
    assertThat(recent.toString()).contains("Aus aaa").contains("Bus bbb").contains("Cus ccc");

    // non-admin -> no pending-users section.
    assertThat(dash.get("pendingUsers").isNull()).isTrue();
  }

  @Test
  void pingsHonorFollowAuthorAndSeenMarker() throws Exception {
    ensureUser("pingOwner");
    ensureUser("pingCommenter");
    long pid = createProject("pingProj", "pingOwner");
    AppUser commenter = users.requireByUsernameOrNull("pingCommenter");
    members.upsert(new ProjectMember((int) pid, commenter.getId(), Role.EDITOR.dbValue()));

    String disc = mvc.perform(post("/api/projects/" + pid + "/discussions").with(csrf()).with(user("pingOwner"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(java.util.Map.of("title", "Need review", "body", "hi"))))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    long did = json.readTree(disc).get("id").asLong();

    // another member comments on the discussion I authored -> a ping for me.
    mvc.perform(post("/api/projects/" + pid + "/discussions/" + did + "/comments").with(csrf())
            .with(user("pingCommenter")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"body\":\"a reply\"}"))
        .andExpect(status().isCreated());

    JsonNode dash = dashboardAs("pingOwner");
    assertThat(dash.get("pings").get("count").asLong()).isEqualTo(1);
    assertThat(dash.get("pings").get("items").get(0).get("title").asString()).isEqualTo("Need review");

    // my own comment must not count as a ping.
    mvc.perform(post("/api/projects/" + pid + "/discussions/" + did + "/comments").with(csrf())
            .with(user("pingOwner")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"body\":\"my own note\"}"))
        .andExpect(status().isCreated());
    assertThat(dashboardAs("pingOwner").get("pings").get("count").asLong()).isEqualTo(1);

    // marking the dashboard seen resets the count.
    mvc.perform(post("/api/me/dashboard/seen").with(csrf()).with(user("pingOwner")))
        .andExpect(status().isNoContent());
    assertThat(dashboardAs("pingOwner").get("pings").get("count").asLong()).isEqualTo(0);
  }

  @Test
  void pendingUsersOnlyForAdmin() throws Exception {
    ensureUser("padminOwner");
    // a self-signup awaiting approval
    AppUser pending = new AppUser();
    pending.setUsername("padminPending");
    pending.setState("PENDING");
    userMapper.insert(pending);

    assertThat(dashboardAs("padminOwner").get("pendingUsers").isNull()).isTrue();

    AppUser me = userMapper.findByUsername("padminOwner");
    me.setAdmin(true);
    userMapper.update(me);
    assertThat(dashboardAs("padminOwner").get("pendingUsers").asLong()).isGreaterThanOrEqualTo(1);
  }
}
