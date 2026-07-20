package org.catalogueoflife.editor.discussion;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class DiscussionPublicIT extends AbstractPostgresIT {

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

  private long createDiscussion(long pid, String title, String body) throws Exception {
    String res = mvc.perform(post("/api/projects/" + pid + "/discussions").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(java.util.Map.of("title", title, "body", body))))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(res).get("id").asLong();
  }

  @Test
  @WithMockUser(username = "pubOwner")
  void publicVisibilityAndRead() throws Exception {
    ensureUser("pubOwner");
    ensureUser("pubViewer");
    long pid = createProject("pubproj");
    AppUser viewer = users.requireByUsernameOrNull("pubViewer");
    members.upsert(new ProjectMember((int) pid, viewer.getId(), Role.VIEWER.dbValue()));

    long did = createDiscussion(pid, "Public topic", "A question about the cats.");
    long internalDid = createDiscussion(pid, "Internal topic", "secret");
    String base = "/api/projects/" + pid + "/discussions";
    String pub = "/api/public/projects/" + pid + "/discussions";

    // not public yet -> anonymous public detail is 404
    mvc.perform(get(pub + "/" + did).with(anonymous())).andExpect(status().isNotFound());

    // a viewer (non-editor) cannot change visibility
    mvc.perform(post(base + "/" + did + "/visibility").with(csrf()).with(user("pubViewer"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"PUBLIC\"}"))
       .andExpect(status().isForbidden());

    // owner marks it public
    mvc.perform(post(base + "/" + did + "/visibility").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"PUBLIC\"}"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.visibility").value("PUBLIC"));

    // anonymous can now read the public discussion
    mvc.perform(get(pub + "/" + did).with(anonymous()))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.title").value("Public topic"))
       .andExpect(jsonPath("$.body").value("A question about the cats."));

    // public list shows only the public discussion
    mvc.perform(get(pub).with(anonymous()))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.length()").value(1))
       .andExpect(jsonPath("$[0].id").value((int) did));

    // the internal discussion stays hidden from the public API
    mvc.perform(get(pub + "/" + internalDid).with(anonymous())).andExpect(status().isNotFound());

    // a comment on the public discussion is publicly readable
    mvc.perform(post(base + "/" + did + "/comments").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"public comment\"}"))
       .andExpect(status().isCreated());
    mvc.perform(get(pub + "/" + did + "/comments").with(anonymous()))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.length()").value(1))
       .andExpect(jsonPath("$[0].body").value("public comment"));

    // comments of an internal discussion are not exposed
    mvc.perform(post(base + "/" + internalDid + "/comments").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"secret comment\"}"))
       .andExpect(status().isCreated());
    mvc.perform(get(pub + "/" + internalDid + "/comments").with(anonymous()))
       .andExpect(status().isNotFound());
  }
}
