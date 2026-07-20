package org.catalogueoflife.editor.name.homotypy;

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
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

// Same helper pattern as HomotypyApiIT (same package): a project + member/usage-creation IT
// against the real Postgres+MockMvc stack. createUsage here is a *parented* variant (unlike
// HomotypyApiIT's, which always creates root usages) so seeded names land in the family's
// subtree and findSubtreeIds(familyId) actually reaches them.
@AutoConfigureMockMvc
@WithMockUser(username = "consOwner")
class ConsolidationApiIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ProjectMemberMapper members;
  @Autowired ObjectMapper json;

  private void ensureUser(String username) {
    if (users.requireByUsernameOrNull(username) == null) users.createLocal(username, "pw", username);
  }

  private long createProjectOwnedBy(String owner) throws Exception {
    ensureUser(owner);
    String body = mvc.perform(post("/api/projects").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"consproj\",\"nomCode\":\"botanical\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  private void addViewer(long pid, String username) {
    ensureUser(username);
    AppUser viewer = users.requireByUsernameOrNull(username);
    members.upsert(new ProjectMember((int) pid, viewer.getId(), Role.VIEWER.dbValue()));
  }

  // Parented create: parentId is omitted from the JSON body (-> null, a root usage) when null.
  private long createUsage(long pid, String name, String authorship, String rank, String statusValue,
      Long parentId) throws Exception {
    String parentField = parentId == null ? "" : ",\"parentId\":" + parentId;
    String body = "{\"scientificName\":\"" + name + "\",\"authorship\":\"" + authorship
        + "\",\"rank\":\"" + rank + "\",\"status\":\"" + statusValue + "\"" + parentField + "}";
    String resp = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf()).with(user("consOwner"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(resp).get("id").asLong();
  }

  @Test
  void scanFindsConflictAndEnforcesAuthz() throws Exception {
    ensureUser("consOwner");
    ensureUser("consViewer");
    ensureUser("consNonMember"); // exists as a user, but deliberately NOT added as a member of pid
    long pid = createProjectOwnedBy("consOwner");
    addViewer(pid, "consViewer");

    // Poaceae (accepted, root) with two homotypic ACCEPTED children in different genera:
    // Poa annua L. and Ochlopoa annua (L.) H.Scholz -- same epithet+basionym-author, so
    // HomotypyDetector.group clusters them, and both being ACCEPTED makes this a genuine
    // conflict (the cluster resolves to 2 distinct accepted names).
    long familyId = createUsage(pid, "Poaceae", "", "family", "accepted", null);
    long poaId = createUsage(pid, "Poa annua", "L.", "species", "accepted", familyId);
    long ochlopoaId = createUsage(pid, "Ochlopoa annua", "(L.) H.Scholz", "species", "accepted", familyId);
    // Give poaId one accepted child so its descendantCount (1) beats ochlopoaId's (0), making
    // suggestedSurvivorId deterministic.
    createUsage(pid, "Poa infirma", "Kunth", "species", "accepted", poaId);

    mvc.perform(get("/api/projects/" + pid + "/usages/" + familyId + "/homotypic/conflicts").with(user("consOwner")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].accepted[?(@.id == " + poaId + ")]").exists())
        .andExpect(jsonPath("$[0].accepted[?(@.id == " + ochlopoaId + ")]").exists())
        .andExpect(jsonPath("$[0].suggestedSurvivorId").value((int) poaId))
        .andExpect(jsonPath("$[0].hasExceptions").value(false));

    // authz: viewer may GET conflicts (200); non-member gets 404.
    mvc.perform(get("/api/projects/" + pid + "/usages/" + familyId + "/homotypic/conflicts").with(user("consViewer")))
        .andExpect(status().isOk());
    mvc.perform(get("/api/projects/" + pid + "/usages/" + familyId + "/homotypic/conflicts")
            .with(user("consNonMember")))
        .andExpect(status().isNotFound());
  }
}
