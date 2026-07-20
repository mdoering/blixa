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
class DiscussionApiIT extends AbstractPostgresIT {

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

  @Test
  @WithMockUser(username = "discOwner")
  void crudSearchFilterSort() throws Exception {
    ensureUser("discOwner");
    long pid = createProject("discproj");
    String base = "/api/projects/" + pid + "/discussions";

    // create (any member) -> 201, per-project id 1, defaults OPEN/INTERNAL, version 0, author resolved
    String createBody = mvc.perform(post(base).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Wrong parent for Panthera\",\"body\":\"The genus placement looks off.\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(1))
        .andExpect(jsonPath("$.status").value("OPEN"))
        .andExpect(jsonPath("$.visibility").value("INTERNAL"))
        .andExpect(jsonPath("$.version").value(0))
        .andExpect(jsonPath("$.authorName").value("discOwner"))
        .andReturn().getResponse().getContentAsString();
    long id = json.readTree(createBody).get("id").asLong();

    mvc.perform(post(base).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Missing synonym for Felis\",\"body\":\"Add the junior synonym.\"}"))
       .andExpect(status().isCreated())
       .andExpect(jsonPath("$.id").value(2));

    // list -> total 2, both present
    mvc.perform(get(base))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.total").value(2))
       .andExpect(jsonPath("$.items.length()").value(2));

    // full-text search over title + body
    mvc.perform(get(base).param("q", "Panthera"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.total").value(1))
       .andExpect(jsonPath("$.items[0].id").value((int) id));
    mvc.perform(get(base).param("q", "synonym"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.total").value(1))
       .andExpect(jsonPath("$.items[0].id").value(2));
    mvc.perform(get(base).param("q", "zzznothing"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.total").value(0));

    // get one
    mvc.perform(get(base + "/" + id))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.title").value("Wrong parent for Panthera"));

    // update with the loaded version -> version bumps to 1
    mvc.perform(put(base + "/" + id).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Wrong parent for Panthera (edited)\",\"body\":\"...\",\"version\":0}"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.version").value(1))
       .andExpect(jsonPath("$.title").value("Wrong parent for Panthera (edited)"));

    // stale version conflicts (the row is now at version 1)
    mvc.perform(put(base + "/" + id).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"x\",\"version\":0}"))
       .andExpect(status().isConflict());

    // status transition OPEN -> RESOLVED (a distinct action; also bumps version)
    mvc.perform(post(base + "/" + id + "/status").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\"}"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.status").value("RESOLVED"));

    // filter by status
    mvc.perform(get(base).param("status", "RESOLVED"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.total").value(1))
       .andExpect(jsonPath("$.items[0].id").value((int) id));

    // filter by author (owner authored both)
    AppUser owner = users.requireByUsernameOrNull("discOwner");
    mvc.perform(get(base).param("authorId", String.valueOf(owner.getId())))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.total").value(2));

    // sort by created ascending -> #1 first
    mvc.perform(get(base).param("sort", "created").param("order", "asc"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.items[0].id").value(1));

    // delete then confirm gone
    mvc.perform(delete(base + "/" + id).with(csrf()))
       .andExpect(status().isNoContent());
    mvc.perform(get(base + "/" + id))
       .andExpect(status().isNotFound());
  }

  @Test
  @WithMockUser(username = "discAzOwner")
  void authorizationRules() throws Exception {
    ensureUser("discAzOwner");
    ensureUser("discAzViewer");
    long pid = createProject("discazproj");
    String base = "/api/projects/" + pid + "/discussions";

    AppUser viewer = users.requireByUsernameOrNull("discAzViewer");
    members.upsert(new ProjectMember((int) pid, viewer.getId(), Role.VIEWER.dbValue()));

    // owner creates a discussion
    String createBody = mvc.perform(post(base).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Owner discussion\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    long ownerDisc = json.readTree(createBody).get("id").asLong();

    // a viewer (any member) MAY start a discussion and read the list
    mvc.perform(get(base).with(user("discAzViewer"))).andExpect(status().isOk());
    mvc.perform(post(base).with(csrf()).with(user("discAzViewer"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Viewer discussion\"}"))
       .andExpect(status().isCreated());

    // but a viewer may NOT change status of / edit / delete someone else's discussion
    mvc.perform(post(base + "/" + ownerDisc + "/status").with(csrf()).with(user("discAzViewer"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\"}"))
       .andExpect(status().isForbidden());
    mvc.perform(put(base + "/" + ownerDisc).with(csrf()).with(user("discAzViewer"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"hijack\",\"version\":0}"))
       .andExpect(status().isForbidden());
    mvc.perform(delete(base + "/" + ownerDisc).with(csrf()).with(user("discAzViewer")))
       .andExpect(status().isForbidden());

    // a non-member sees the project (and thus its discussions) as 404
    ensureUser("discAzStranger");
    mvc.perform(get(base).with(user("discAzStranger"))).andExpect(status().isNotFound());
  }
}
