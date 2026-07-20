package org.catalogueoflife.editor.discussion;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
import org.catalogueoflife.editor.user.AppUserMapper;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class DiscussionFollowIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired AppUserMapper userMapper;
  @Autowired ProjectMemberMapper members;
  @Autowired ObjectMapper json;

  @MockitoBean EmailService email; // no real SMTP -- verify the notifier targets the right followers

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
  @WithMockUser(username = "folOwner")
  void followUnfollowAndNotify() throws Exception {
    ensureUser("folOwner");
    ensureUser("folViewer");
    // give the owner an email so the notifier has somewhere to send
    AppUser owner = users.requireByUsernameOrNull("folOwner");
    owner.setEmail("owner@example.org");
    userMapper.update(owner);

    long pid = createProject("folproj");
    AppUser viewer = users.requireByUsernameOrNull("folViewer");
    members.upsert(new ProjectMember((int) pid, viewer.getId(), Role.VIEWER.dbValue()));

    long did = createDiscussion(pid, "Topic", "body");
    String base = "/api/projects/" + pid + "/discussions/" + did;

    // the author auto-follows on create
    mvc.perform(get(base))
       .andExpect(jsonPath("$.following").value(true))
       .andExpect(jsonPath("$.followerCount").value(1));

    // the viewer isn't following yet
    mvc.perform(get(base).with(user("folViewer")))
       .andExpect(jsonPath("$.following").value(false))
       .andExpect(jsonPath("$.followerCount").value(1));

    // viewer follows -> count 2
    mvc.perform(post(base + "/follow").with(csrf()).with(user("folViewer"))).andExpect(status().isOk());
    mvc.perform(get(base).with(user("folViewer")))
       .andExpect(jsonPath("$.following").value(true))
       .andExpect(jsonPath("$.followerCount").value(2));

    // viewer unfollows -> back to 1
    mvc.perform(delete(base + "/follow").with(csrf()).with(user("folViewer")))
       .andExpect(status().isNoContent());
    mvc.perform(get(base).with(user("folViewer")))
       .andExpect(jsonPath("$.followerCount").value(1));

    // viewer comments -> re-follows, and the owner (a follower, not the actor) is emailed
    mvc.perform(post(base + "/comments").with(csrf()).with(user("folViewer"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"hi\"}"))
       .andExpect(status().isCreated());
    mvc.perform(get(base).with(user("folViewer")))
       .andExpect(jsonPath("$.followerCount").value(2));

    verify(email).send(eq("owner@example.org"), anyString(), anyString());
  }
}
