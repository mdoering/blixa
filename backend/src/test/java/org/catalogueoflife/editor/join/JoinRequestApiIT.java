package org.catalogueoflife.editor.join;

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
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
class JoinRequestApiIT extends AbstractPostgresIT {

  private static final String VALID_ORCID = "0000-0002-1825-0097";

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ProjectMemberMapper members;
  @Autowired ObjectMapper json;

  private void ensureUser(String u) {
    if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u);
  }

  // Creates a project as `owner`, gives it a license (required to go public), and flips it public.
  private int makePublicProject(String owner) throws Exception {
    ensureUser(owner);
    String b = mvc.perform(post("/api/projects").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Joinable\"}"))
        .andReturn().getResponse().getContentAsString();
    int pid = json.readTree(b).get("id").asInt();
    mvc.perform(put("/api/projects/" + pid + "/metadata").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Joinable\",\"license\":\"CC0-1.0\"}"))
       .andExpect(status().isOk());
    mvc.perform(put("/api/projects/" + pid + "/public").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON).content("{\"public\":true}"))
       .andExpect(status().isOk());
    return pid;
  }

  @Test
  void publicJoinRequestIsIdempotentAndOwnerCanListCountAndDismiss() throws Exception {
    int pid = makePublicProject("joinOwner1");

    // Unauthenticated POST -- no user(), and no csrf() needed since /api/public/** is CSRF-exempt.
    mvc.perform(post("/api/public/projects/" + pid + "/join")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"orcid\":\"" + VALID_ORCID + "\",\"name\":\"Vera Visitor\",\"message\":\"let me in\"}"))
       .andExpect(status().is2xxSuccessful());

    // A repeat submission from the same ORCID is a silent no-op (idempotent), not an error.
    mvc.perform(post("/api/public/projects/" + pid + "/join")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"orcid\":\"" + VALID_ORCID + "\",\"name\":\"Vera Visitor\",\"message\":\"let me in again\"}"))
       .andExpect(status().is2xxSuccessful());

    // Invalid ORCID format -> 400.
    mvc.perform(post("/api/public/projects/" + pid + "/join")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"orcid\":\"not-an-orcid\"}"))
       .andExpect(status().isBadRequest());

    // Owner sees exactly one pending request, despite the duplicate submission above.
    String listBody = mvc.perform(get("/api/projects/" + pid + "/join-requests").with(user("joinOwner1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].orcid").value(VALID_ORCID))
        .andExpect(jsonPath("$[0].name").value("Vera Visitor"))
        .andReturn().getResponse().getContentAsString();
    int requestId = json.readTree(listBody).get(0).get("id").asInt();

    mvc.perform(get("/api/projects/" + pid + "/join-requests/count").with(user("joinOwner1")))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.count").value(1));

    // A non-owner member (editor) is forbidden from viewing join requests.
    ensureUser("joinEditor1");
    AppUser editor = users.requireByUsernameOrNull("joinEditor1");
    members.upsert(new ProjectMember(pid, editor.getId(), Role.EDITOR.dbValue()));
    mvc.perform(get("/api/projects/" + pid + "/join-requests").with(user("joinEditor1")))
       .andExpect(status().isForbidden());

    // Owner dismisses the request.
    mvc.perform(delete("/api/projects/" + pid + "/join-requests/" + requestId)
            .with(csrf()).with(user("joinOwner1")))
       .andExpect(status().isNoContent());

    mvc.perform(get("/api/projects/" + pid + "/join-requests").with(user("joinOwner1")))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.length()").value(0));
  }
}
