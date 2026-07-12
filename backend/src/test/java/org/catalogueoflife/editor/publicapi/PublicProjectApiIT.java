package org.catalogueoflife.editor.publicapi;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.catalogueoflife.editor.project.ProjectMember;
import org.catalogueoflife.editor.project.ProjectMemberMapper;
import org.catalogueoflife.editor.project.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
class PublicProjectApiIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ProjectMemberMapper members;
  @Autowired ObjectMapper json;

  private void ensureUser(String u) { if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u); }

  private int makePublicProject(String owner) throws Exception {
    ensureUser(owner);
    String b = mvc.perform(post("/api/projects").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Public One\",\"alias\":\"pub1\"}"))
        .andReturn().getResponse().getContentAsString();
    int pid = json.readTree(b).get("id").asInt();
    mvc.perform(put("/api/projects/" + pid + "/public").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON).content("{\"public\":true}")).andExpect(status().isOk());
    return pid;
  }

  @Test
  void privateProjectIsNotExposed() throws Exception {
    ensureUser("po");
    String b = mvc.perform(post("/api/projects").with(csrf()).with(user("po"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Private\"}"))
        .andReturn().getResponse().getContentAsString();
    int pid = json.readTree(b).get("id").asInt();
    mvc.perform(get("/api/public/projects/" + pid)).andExpect(status().isNotFound());
    mvc.perform(get("/api/public/projects"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$[?(@.id == " + pid + ")]").isEmpty());
  }

  @Test
  void publicProjectExposesInfoAndContributorsExcludeViewers() throws Exception {
    int pid = makePublicProject("owner1");
    ensureUser("viewer1");
    AppUser v = users.requireByUsernameOrNull("viewer1");
    members.upsert(new ProjectMember(pid, v.getId(), Role.VIEWER.dbValue()));

    // anonymous read
    mvc.perform(get("/api/public/projects/" + pid))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.id").value(pid))
       .andExpect(jsonPath("$.title").value("Public One"))
       .andExpect(jsonPath("$.contributors[?(@.role == 'viewer')]").isEmpty())
       .andExpect(jsonPath("$.contributors[?(@.role == 'owner')]").exists())
       .andExpect(jsonPath("$.contributors[0].email").doesNotExist());
    // alias resolves to the same canonical id
    mvc.perform(get("/api/public/projects/pub1"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.id").value(pid));
  }
}
