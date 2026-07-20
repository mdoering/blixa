package org.catalogueoflife.editor.discussion;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class DiscussionChangeIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ProjectMemberMapper members;
  @Autowired ObjectMapper json;

  private void ensureUser(String username) {
    if (users.requireByUsernameOrNull(username) == null) users.createLocal(username, "pw", username);
  }

  private long createProject(String title) throws Exception {
    String body = mvc.perform(post("/api/projects").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"" + title + "\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  private long createUsage(long pid, String name) throws Exception {
    String body = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"" + name + "\",\"rank\":\"species\",\"status\":\"accepted\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  private long createDiscussion(long pid, String title, String body) throws Exception {
    String res = mvc.perform(post("/api/projects/" + pid + "/discussions").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(java.util.Map.of("title", title, "body", body))))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(res).get("id").asLong();
  }

  @Test
  @WithMockUser(username = "lnkOwner")
  void linkListAndUnlinkChanges() throws Exception {
    ensureUser("lnkOwner");
    ensureUser("lnkViewer");
    long pid = createProject("lnkproj");
    AppUser viewer = users.requireByUsernameOrNull("lnkViewer");
    members.upsert(new ProjectMember((int) pid, viewer.getId(), Role.VIEWER.dbValue()));

    createUsage(pid, "Panthera leo"); // produces an audit change
    long did = createDiscussion(pid, "Placement", "body");

    // grab a change id from the project's changelog
    String changes = mvc.perform(get("/api/projects/" + pid + "/changes"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    int changeId = json.readTree(changes).get(0).get("id").asInt();

    String base = "/api/projects/" + pid + "/discussions/" + did + "/changes";

    // nothing linked yet
    mvc.perform(get(base)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

    // a viewer cannot link
    mvc.perform(post(base).with(csrf()).with(user("lnkViewer"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"changeId\":" + changeId + "}"))
       .andExpect(status().isForbidden());

    // owner links the change
    mvc.perform(post(base).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"changeId\":" + changeId + "}"))
       .andExpect(status().is2xxSuccessful());

    // it's listed, with the full change row (operation/entity)
    mvc.perform(get(base))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.length()").value(1))
       .andExpect(jsonPath("$[0].id").value(changeId))
       .andExpect(jsonPath("$[0].entityType").value("name_usage"));

    // unlink
    mvc.perform(delete(base + "/" + changeId).with(csrf())).andExpect(status().isNoContent());
    mvc.perform(get(base)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
  }
}
