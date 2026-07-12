package org.catalogueoflife.editor.project;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@WithMockUser(username = "pubOwner")
class ProjectPublicApiIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ProjectMemberMapper members;
  @Autowired ObjectMapper json;

  private void ensureUser(String u) {
    if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u);
  }

  private int createProject(String owner) throws Exception {
    ensureUser(owner);
    String body = mvc.perform(post("/api/projects").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Pub\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.public").value(false))   // default false, serialized as "public"
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asInt();
  }

  // A license is required before a project can be made public (B2) -- set one via the metadata
  // endpoint so the public-toggle tests below exercise the allowed path.
  private void setLicense(int pid, String owner) throws Exception {
    mvc.perform(put("/api/projects/" + pid + "/metadata").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Pub\",\"license\":\"CC0-1.0\"}"))
       .andExpect(status().isOk());
  }

  @Test
  void ownerTogglesPublic() throws Exception {
    int pid = createProject("pubOwner");
    setLicense(pid, "pubOwner");
    mvc.perform(put("/api/projects/" + pid + "/public").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"public\":true}"))
       .andExpect(status().isOk());
    mvc.perform(get("/api/projects/" + pid))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.public").value(true));
  }

  @Test
  void nonOwnerCannotTogglePublic() throws Exception {
    int pid = createProject("pubOwner");
    ensureUser("pubEditor");
    AppUser ed = users.requireByUsernameOrNull("pubEditor");
    members.upsert(new ProjectMember(pid, ed.getId(), Role.EDITOR.dbValue()));
    mvc.perform(put("/api/projects/" + pid + "/public").with(csrf()).with(user("pubEditor"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"public\":true}"))
       .andExpect(status().isForbidden());
  }

  // B2: a project's license must be set before it can be made public -- the license-less project
  // is rejected with 400, and the same request succeeds once a license is set.
  @Test
  void makePublicRequiresLicense() throws Exception {
    int pid = createProject("pubOwner");
    mvc.perform(put("/api/projects/" + pid + "/public").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"public\":true}"))
       .andExpect(status().isBadRequest());

    setLicense(pid, "pubOwner");

    mvc.perform(put("/api/projects/" + pid + "/public").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"public\":true}"))
       .andExpect(status().isOk());
    mvc.perform(get("/api/projects/" + pid))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.public").value(true));
  }
}
