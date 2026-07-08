package org.catalogueoflife.editor.tree;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class TreeApiIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
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
  }
}
