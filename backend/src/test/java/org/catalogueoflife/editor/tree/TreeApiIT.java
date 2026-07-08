package org.catalogueoflife.editor.tree;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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
class TreeApiIT extends AbstractPostgresIT {

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

  private long createUsage(long pid, String name, String rank, String statusValue, Long parentId)
      throws Exception {
    String parentJson = parentId == null ? "" : ",\"parentId\":" + parentId;
    String body = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"" + name + "\",\"rank\":\"" + rank
                + "\",\"status\":\"" + statusValue + "\"" + parentJson + "}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  @Test
  @WithMockUser(username = "treeOwner")
  void rootsChildrenPathAndAuthz() throws Exception {
    ensureUser("treeOwner");
    ensureUser("treeOutsider");
    long pid = createProject("treeproj");

    // Animalia -> Chordata -> Mammalia, plus a second root Plantae.
    long animaliaId = createUsage(pid, "Animalia", "kingdom", "accepted", null);
    long chordataId = createUsage(pid, "Chordata", "phylum", "accepted", animaliaId);
    long mammaliaId = createUsage(pid, "Mammalia", "class", "accepted", chordataId);
    long plantaeId = createUsage(pid, "Plantae", "kingdom", "accepted", null);

    // a synonym, linked to Mammalia via synonym-of -- it must NOT show up as a tree node
    // anywhere (not a root, not a child of Mammalia, and Mammalia's childCount stays 0).
    long synId = createUsage(pid, "Mammalia synonym", "class", "synonym", null);
    mvc.perform(put("/api/projects/" + pid + "/usages/" + synId + "/synonym-of/" + mammaliaId)
            .with(csrf()))
        .andExpect(status().isNoContent());

    // roots: Animalia (childCount 1) + Plantae (childCount 0), alphabetically ordered; no synonym.
    mvc.perform(get("/api/projects/" + pid + "/tree/roots"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].id").value(animaliaId))
        .andExpect(jsonPath("$[0].scientificName").value("Animalia"))
        .andExpect(jsonPath("$[0].childCount").value(1))
        .andExpect(jsonPath("$[1].id").value(plantaeId))
        .andExpect(jsonPath("$[1].childCount").value(0));

    // children of Animalia: just Chordata, which itself has one child (Mammalia).
    mvc.perform(get("/api/projects/" + pid + "/tree/children/" + animaliaId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(chordataId))
        .andExpect(jsonPath("$[0].scientificName").value("Chordata"))
        .andExpect(jsonPath("$[0].childCount").value(1));

    // children of Mammalia: empty -- the linked synonym is NOT a tree child.
    mvc.perform(get("/api/projects/" + pid + "/tree/children/" + mammaliaId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));

    // ancestor path of Mammalia is root-first: Animalia, Chordata, Mammalia.
    mvc.perform(get("/api/projects/" + pid + "/tree/path/" + mammaliaId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].scientificName").value("Animalia"))
        .andExpect(jsonPath("$[1].scientificName").value("Chordata"))
        .andExpect(jsonPath("$[2].scientificName").value("Mammalia"));

    // a root's own path is just itself.
    mvc.perform(get("/api/projects/" + pid + "/tree/path/" + plantaeId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].scientificName").value("Plantae"));

    // a non-member gets 404, not an empty/leaked result.
    mvc.perform(get("/api/projects/" + pid + "/tree/roots").with(user("treeOutsider")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").exists());
    mvc.perform(get("/api/projects/" + pid + "/tree/children/" + animaliaId).with(user("treeOutsider")))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/projects/" + pid + "/tree/path/" + mammaliaId).with(user("treeOutsider")))
        .andExpect(status().isNotFound());

    // a synonym is never a tree node, so its "path" must not leak out as a 200 -- the recursive
    // CTE's base case is filtered to status = 'ACCEPTED', producing an empty path that
    // TreeService.path turns into 404 (see findPath's Javadoc).
    mvc.perform(get("/api/projects/" + pid + "/tree/path/" + synId))
        .andExpect(status().isNotFound());
  }

  private org.springframework.test.web.servlet.ResultActions move(long pid, long id, Long parentId,
      int version, String asUser) throws Exception {
    String parentJson = parentId == null ? "null" : String.valueOf(parentId);
    var req = put("/api/projects/" + pid + "/tree/usages/" + id + "/parent").with(csrf())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"parentId\":" + parentJson + ",\"version\":" + version + "}");
    if (asUser != null) {
      req = req.with(user(asUser));
    }
    return mvc.perform(req);
  }

  @Test
  @WithMockUser(username = "treeMoveOwner")
  void moveReparentCycleSafe() throws Exception {
    ensureUser("treeMoveOwner");
    ensureUser("treeMoveViewer");
    long pid = createProject("treemoveproj");

    AppUser viewer = users.requireByUsernameOrNull("treeMoveViewer");
    members.upsert(new ProjectMember((int) pid, viewer.getId(), Role.VIEWER.dbValue()));

    // Animalia -> Chordata -> Mammalia, plus a second root Plantae, plus a synonym of Mammalia
    // (never a valid move target -- it isn't ACCEPTED).
    long animaliaId = createUsage(pid, "Animalia", "kingdom", "accepted", null);
    long chordataId = createUsage(pid, "Chordata", "phylum", "accepted", animaliaId);
    long mammaliaId = createUsage(pid, "Mammalia", "class", "accepted", chordataId);
    long plantaeId = createUsage(pid, "Plantae", "kingdom", "accepted", null);
    long synId = createUsage(pid, "Mammalia synonym", "class", "synonym", null);
    mvc.perform(put("/api/projects/" + pid + "/usages/" + synId + "/synonym-of/" + mammaliaId)
            .with(csrf()))
        .andExpect(status().isNoContent());

    // Cycle guard: Mammalia is still Animalia's descendant (via Chordata) at this point, so
    // making Animalia a child of Mammalia would loop the tree back on itself -- rejected.
    move(pid, animaliaId, mammaliaId, 0, null).andExpect(status().isBadRequest());

    // A viewer may not move anything, regardless of whether the move would otherwise be valid.
    move(pid, mammaliaId, plantaeId, 0, "treeMoveViewer").andExpect(status().isForbidden());

    // A synonym is not a valid parent -- it was never a tree node.
    move(pid, mammaliaId, synId, 0, null).andExpect(status().isBadRequest());

    // Stale version on the moved node itself: reparent's optimistic-lock CAS matches no row.
    move(pid, mammaliaId, plantaeId, 99, null).andExpect(status().isConflict());

    // The actual move: Mammalia leaves Chordata and becomes a direct child of Plantae.
    move(pid, mammaliaId, plantaeId, 0, null).andExpect(status().isOk());

    mvc.perform(get("/api/projects/" + pid + "/tree/children/" + plantaeId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(mammaliaId))
        .andExpect(jsonPath("$[0].scientificName").value("Mammalia"));
    mvc.perform(get("/api/projects/" + pid + "/tree/children/" + chordataId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(get("/api/projects/" + pid + "/tree/path/" + mammaliaId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].scientificName").value("Plantae"))
        .andExpect(jsonPath("$[1].scientificName").value("Mammalia"));

    // parentId: null moves Chordata (still version 0 -- untouched by the Mammalia move above) to
    // the top level, making it a third root; Animalia loses its only child.
    move(pid, chordataId, null, 0, null).andExpect(status().isOk());

    mvc.perform(get("/api/projects/" + pid + "/tree/roots"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].scientificName").value("Animalia"))
        .andExpect(jsonPath("$[0].childCount").value(0))
        .andExpect(jsonPath("$[1].scientificName").value("Chordata"))
        .andExpect(jsonPath("$[1].childCount").value(0))
        .andExpect(jsonPath("$[2].scientificName").value("Plantae"))
        .andExpect(jsonPath("$[2].childCount").value(1));
  }
}
