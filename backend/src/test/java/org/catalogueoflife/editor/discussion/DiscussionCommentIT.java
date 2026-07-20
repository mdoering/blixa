package org.catalogueoflife.editor.discussion;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class DiscussionCommentIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired AppUserMapper userMapper;
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
  @WithMockUser(username = "cmtOwner")
  void commentsMentionsAndReverseLinks() throws Exception {
    ensureUser("cmtOwner");
    long pid = createProject("cmtproj");
    long leo = createUsage(pid, "Panthera leo");
    long felis = createUsage(pid, "Felis catus");

    // a user with an ORCID, to resolve an @orcid mention
    AppUser olaf = new AppUser();
    olaf.setOrcid("0000-0001-2345-6789");
    olaf.setUsername("olaf");
    olaf.setDisplayName("Olaf Banki");
    userMapper.insert(olaf);

    // discussion body references #<leo>, an @<orcid>, and an @<username>
    long did = createDiscussion(pid, "Placement of the cats",
        "The parent of #" + leo + " looks wrong, per @0000-0001-2345-6789 (aka @olaf).");
    String base = "/api/projects/" + pid + "/discussions/" + did;

    // GET discussion resolves mentions: #id -> scientific name, @orcid/@username -> display name
    mvc.perform(get(base))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.mentions.usages['" + leo + "']").value("Panthera leo"))
       .andExpect(jsonPath("$.mentions.users['0000-0001-2345-6789'].label").value("Olaf Banki"))
       .andExpect(jsonPath("$.mentions.users['olaf'].label").value("Olaf Banki"));

    // reverse-link: the mentioned name lists this discussion
    mvc.perform(get("/api/projects/" + pid + "/usages/" + leo + "/discussions"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.length()").value(1))
       .andExpect(jsonPath("$[0].id").value((int) did));
    // an unmentioned name lists none yet
    mvc.perform(get("/api/projects/" + pid + "/usages/" + felis + "/discussions"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.length()").value(0));

    // add a comment that references #<felis>
    String cbody = mvc.perform(post(base + "/comments").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"body\":\"Also compare with #" + felis + ".\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(1))
        .andExpect(jsonPath("$.mentions.usages['" + felis + "']").value("Felis catus"))
        .andReturn().getResponse().getContentAsString();
    long cid = json.readTree(cbody).get("id").asLong();

    // comment list returns it
    mvc.perform(get(base + "/comments"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.length()").value(1))
       .andExpect(jsonPath("$[0].id").value((int) cid));

    // the comment's #felis reference now reverse-links felis to this discussion
    mvc.perform(get("/api/projects/" + pid + "/usages/" + felis + "/discussions"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.length()").value(1))
       .andExpect(jsonPath("$[0].id").value((int) did));

    // edit the comment (author) -> version bumps
    mvc.perform(put(base + "/comments/" + cid).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"body\":\"edited\",\"version\":0}"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.version").value(1))
       .andExpect(jsonPath("$.body").value("edited"));

    // editing the comment removed its #felis reference -> reverse-link gone
    mvc.perform(get("/api/projects/" + pid + "/usages/" + felis + "/discussions"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.length()").value(0));

    // delete the comment
    mvc.perform(delete(base + "/comments/" + cid).with(csrf()))
       .andExpect(status().isNoContent());
    mvc.perform(get(base + "/comments"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  @WithMockUser(username = "cmtAzOwner")
  void commentAuthorization() throws Exception {
    ensureUser("cmtAzOwner");
    ensureUser("cmtAzViewer");
    long pid = createProject("cmtazproj");
    long did = createDiscussion(pid, "Topic", "body");
    String base = "/api/projects/" + pid + "/discussions/" + did;

    AppUser viewer = users.requireByUsernameOrNull("cmtAzViewer");
    members.upsert(new ProjectMember((int) pid, viewer.getId(), Role.VIEWER.dbValue()));

    // owner posts a comment
    String cbody = mvc.perform(post(base + "/comments").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"owner comment\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    long ownerComment = json.readTree(cbody).get("id").asLong();

    // a viewer (any member) may comment
    mvc.perform(post(base + "/comments").with(csrf()).with(user("cmtAzViewer"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"viewer comment\"}"))
       .andExpect(status().isCreated());

    // but a viewer may NOT edit/delete someone else's comment
    mvc.perform(put(base + "/comments/" + ownerComment).with(csrf()).with(user("cmtAzViewer"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"hijack\",\"version\":0}"))
       .andExpect(status().isForbidden());
    mvc.perform(delete(base + "/comments/" + ownerComment).with(csrf()).with(user("cmtAzViewer")))
       .andExpect(status().isForbidden());
  }
}
