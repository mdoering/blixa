package org.catalogueoflife.editor.release;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectMetricsIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
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

  private void createUsage(long pid, String name, String statusValue, String actor) throws Exception {
    mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf()).with(user(actor))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"" + name + "\",\"rank\":\"species\",\"status\":\""
                + statusValue + "\"}"))
        .andExpect(status().isCreated());
  }

  @Test
  void liveMetricsCountByStatusAndRank() throws Exception {
    ensureUser("metricsOwner");
    long pid = createProject("metricsproj", "metricsOwner");
    createUsage(pid, "Aus aaa", "accepted", "metricsOwner");
    createUsage(pid, "Bus bbb", "accepted", "metricsOwner");
    createUsage(pid, "Cus ccc", "synonym", "metricsOwner");

    mvc.perform(get("/api/projects/" + pid + "/metrics").with(user("metricsOwner")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.acceptedByRank.species").value(2))
        .andExpect(jsonPath("$.synonymsByRank.species").value(1))
        .andExpect(jsonPath("$.changesSinceLastRelease").exists());
  }

  @Test
  void viewerMayReadMetrics() throws Exception {
    ensureUser("metricsOwner2");
    ensureUser("metricsViewer");
    long pid = createProject("metricsproj2", "metricsOwner2");
    AppUser viewer = users.requireByUsernameOrNull("metricsViewer");
    members.upsert(new ProjectMember((int) pid, viewer.getId(), Role.VIEWER.dbValue()));

    mvc.perform(get("/api/projects/" + pid + "/metrics").with(user("metricsViewer")))
        .andExpect(status().isOk());

    // a non-member is refused.
    ensureUser("metricsOutsider");
    mvc.perform(get("/api/projects/" + pid + "/metrics").with(user("metricsOutsider")))
        .andExpect(status().isNotFound());
  }
}
