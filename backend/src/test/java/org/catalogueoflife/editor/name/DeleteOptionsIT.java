package org.catalogueoflife.editor.name;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
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
class DeleteOptionsIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

  private void ensureUser(String u) {
    if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u);
  }

  private long createProject(String title) throws Exception {
    String body = mvc.perform(post("/api/projects").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"" + title + "\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  private long usage(long pid, String name, String rank, String statusVal, Long parentId) throws Exception {
    String content = "{\"scientificName\":\"" + name + "\",\"rank\":\"" + rank + "\",\"status\":\""
        + statusVal + "\"" + (parentId == null ? "" : ",\"parentId\":" + parentId) + "}";
    String body = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(content))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  private void link(long pid, long synId, long acceptedId) throws Exception {
    mvc.perform(put("/api/projects/" + pid + "/usages/" + synId + "/synonym-of/" + acceptedId).with(csrf()))
       .andExpect(status().is2xxSuccessful());
  }

  @Test
  @WithMockUser(username = "delOwner")
  void deleteModesAndReparent() throws Exception {
    ensureUser("delOwner");
    long pid = createProject("delproj");
    long fam = usage(pid, "Felidae", "family", "accepted", null);
    long genus = usage(pid, "Panthera", "genus", "accepted", fam);
    long sp1 = usage(pid, "Panthera leo", "species", "accepted", genus);
    usage(pid, "Panthera tigris", "species", "accepted", genus);

    // FOCAL_ONLY: the genus goes, its species reparent to the family (grandparent)
    mvc.perform(delete("/api/projects/" + pid + "/usages/" + genus).with(csrf()).param("mode", "FOCAL_ONLY"))
       .andExpect(status().isNoContent());
    mvc.perform(get("/api/projects/" + pid + "/usages/" + genus)).andExpect(status().isNotFound());
    mvc.perform(get("/api/projects/" + pid + "/usages/" + sp1))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.parentId").value((int) fam));

    // SUBTREE: focal + all descendants gone
    long g2 = usage(pid, "Felis", "genus", "accepted", fam);
    long g2sp = usage(pid, "Felis catus", "species", "accepted", g2);
    mvc.perform(delete("/api/projects/" + pid + "/usages/" + g2).with(csrf()).param("mode", "SUBTREE"))
       .andExpect(status().isNoContent());
    mvc.perform(get("/api/projects/" + pid + "/usages/" + g2)).andExpect(status().isNotFound());
    mvc.perform(get("/api/projects/" + pid + "/usages/" + g2sp)).andExpect(status().isNotFound());
    mvc.perform(get("/api/projects/" + pid + "/usages/" + fam)).andExpect(status().isOk());

    // reparentTo a chosen new parent
    long g3 = usage(pid, "Neofelis", "genus", "accepted", fam);
    long g3sp = usage(pid, "Neofelis nebulosa", "species", "accepted", g3);
    long other = usage(pid, "Lynx", "genus", "accepted", fam);
    mvc.perform(delete("/api/projects/" + pid + "/usages/" + g3).with(csrf())
            .param("mode", "FOCAL_ONLY").param("reparentTo", String.valueOf(other)))
       .andExpect(status().isNoContent());
    mvc.perform(get("/api/projects/" + pid + "/usages/" + g3sp))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.parentId").value((int) other));

    // reparentTo INSIDE the deleted subtree -> 400
    long g4 = usage(pid, "Puma", "genus", "accepted", fam);
    long g4sp = usage(pid, "Puma concolor", "species", "accepted", g4);
    mvc.perform(delete("/api/projects/" + pid + "/usages/" + g4).with(csrf())
            .param("mode", "FOCAL_ONLY").param("reparentTo", String.valueOf(g4sp)))
       .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(username = "delSynOwner")
  void focalOnlyKeepsSynonymsWithSynonymsRemovesThem() throws Exception {
    ensureUser("delSynOwner");
    long pid = createProject("delsynproj");

    // FOCAL_ONLY: the synonym survives (just unlinked)
    long acc1 = usage(pid, "Aus", "genus", "accepted", null);
    long syn1 = usage(pid, "Xus", "genus", "synonym", null);
    link(pid, syn1, acc1);
    mvc.perform(delete("/api/projects/" + pid + "/usages/" + acc1).with(csrf()).param("mode", "FOCAL_ONLY"))
       .andExpect(status().isNoContent());
    mvc.perform(get("/api/projects/" + pid + "/usages/" + syn1)).andExpect(status().isOk());

    // WITH_SYNONYMS: the synonym goes too
    long acc2 = usage(pid, "Bus", "genus", "accepted", null);
    long syn2 = usage(pid, "Yus", "genus", "synonym", null);
    link(pid, syn2, acc2);
    mvc.perform(delete("/api/projects/" + pid + "/usages/" + acc2).with(csrf()).param("mode", "WITH_SYNONYMS"))
       .andExpect(status().isNoContent());
    mvc.perform(get("/api/projects/" + pid + "/usages/" + acc2)).andExpect(status().isNotFound());
    mvc.perform(get("/api/projects/" + pid + "/usages/" + syn2)).andExpect(status().isNotFound());
  }
}
