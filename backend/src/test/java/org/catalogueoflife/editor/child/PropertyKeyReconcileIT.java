package org.catalogueoflife.editor.child;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.child.dto.PropertyKeyMergeRequest;
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

// Shared taxon property keys: the overview facet (used ∪ defined keys with counts + descriptions),
// defining/describing a key (PUT), removing a definition (DELETE), the editor gate, and reconcile
// (POST .../merge rewrites property.property + folds definitions). Mirrors ContainerTitleReconcileIT
// for the journal-name equivalent.
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "propKeyOwner")
class PropertyKeyReconcileIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ProjectMemberMapper members;
  @Autowired ObjectMapper json;

  private void ensureUser(String u) {
    if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u);
  }

  private long createProject() throws Exception {
    String b = mvc.perform(post("/api/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"propkeys\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(b).get("id").asLong();
  }

  private long createUsage(long pid, String name) throws Exception {
    String b = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"" + name + "\",\"rank\":\"species\",\"status\":\"accepted\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(b).get("id").asLong();
  }

  private void addProperty(long pid, long uid, String key, String value) throws Exception {
    mvc.perform(post("/api/projects/" + pid + "/usages/" + uid + "/properties").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"property\":\"" + key + "\",\"value\":\"" + value + "\"}"))
        .andExpect(status().isCreated());
  }

  @Test
  void facetDefineMergeAndDelete() throws Exception {
    ensureUser("propKeyOwner");
    ensureUser("propKeyViewer");
    long pid = createProject();
    long u = createUsage(pid, "Panthera leo");

    AppUser viewer = users.requireByUsernameOrNull("propKeyViewer");
    members.upsert(new ProjectMember((int) pid, viewer.getId(), Role.VIEWER.dbValue()));

    // used keys: "body mass" x2 (a spelling variant of the camelCase "bodyMass"), "bodyMass" x1
    addProperty(pid, u, "body mass", "190 kg");
    addProperty(pid, u, "body mass", "200 kg");
    addProperty(pid, u, "bodyMass", "210 kg");

    // define/describe the spelled-out variant (note the space -- keys travel in the body, not the
    // path), and a brand-new key that is not used anywhere yet
    mvc.perform(put("/api/projects/" + pid + "/property-keys").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"key\":\"body mass\",\"description\":\"Adult body mass\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.key").value("body mass"))
        .andExpect(jsonPath("$.count").value(2))
        .andExpect(jsonPath("$.description").value("Adult body mass"));
    mvc.perform(put("/api/projects/" + pid + "/property-keys").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"key\":\"karyotype\",\"description\":\"Chromosome set\"}"))
        .andExpect(status().isOk());

    // overview: used ∪ defined keys, ordered by count desc then key. "karyotype" is defined-but-unused
    // (count 0); "body mass" carries its description, "bodyMass" is used-but-undefined (null desc).
    mvc.perform(get("/api/projects/" + pid + "/property-keys"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].key").value("body mass"))
        .andExpect(jsonPath("$[0].count").value(2))
        .andExpect(jsonPath("$[0].description").value("Adult body mass"))
        .andExpect(jsonPath("$[1].key").value("bodyMass"))
        .andExpect(jsonPath("$[1].count").value(1))
        .andExpect(jsonPath("$[2].key").value("karyotype"))
        .andExpect(jsonPath("$[2].count").value(0))
        .andExpect(jsonPath("$[2].description").value("Chromosome set"));

    // a viewer may read the overview but not define or merge
    mvc.perform(get("/api/projects/" + pid + "/property-keys").with(user("propKeyViewer")))
        .andExpect(status().isOk());
    mvc.perform(put("/api/projects/" + pid + "/property-keys").with(csrf()).with(user("propKeyViewer"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"bodyMass\",\"description\":\"x\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/projects/" + pid + "/property-keys/merge").with(csrf()).with(user("propKeyViewer"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(new PropertyKeyMergeRequest("bodyMass",
                java.util.List.of("body mass", "bodyMass")))))
        .andExpect(status().isForbidden());

    // empty variants -> 400
    mvc.perform(post("/api/projects/" + pid + "/property-keys/merge").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(new PropertyKeyMergeRequest("bodyMass", java.util.List.of()))))
        .andExpect(status().isBadRequest());

    // merge: rewrite every property whose key is a variant to the canonical "bodyMass" (updated=3),
    // and fold the definitions -- canonical had no description, so it inherits the variant's.
    mvc.perform(post("/api/projects/" + pid + "/property-keys/merge").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(new PropertyKeyMergeRequest("bodyMass",
                java.util.List.of("body mass", "bodyMass")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updated").value(3));

    // overview after merge: "bodyMass" count 3 with the folded description; the "body mass" definition
    // is gone; "karyotype" (count 0) still present.
    mvc.perform(get("/api/projects/" + pid + "/property-keys"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].key").value("bodyMass"))
        .andExpect(jsonPath("$[0].count").value(3))
        .andExpect(jsonPath("$[0].description").value("Adult body mass"))
        .andExpect(jsonPath("$[1].key").value("karyotype"))
        .andExpect(jsonPath("$[1].count").value(0));

    // DELETE removes only the definition; an unused key with no definition then vanishes entirely.
    mvc.perform(delete("/api/projects/" + pid + "/property-keys").param("key", "karyotype").with(csrf()))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/projects/" + pid + "/property-keys"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].key").value("bodyMass"));
  }
}
