package org.catalogueoflife.editor.name.bulk;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.catalogueoflife.editor.project.ProjectMemberMapper;
import org.catalogueoflife.editor.project.ProjectMember;
import org.catalogueoflife.editor.project.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@WithMockUser(username = "bulkIns")
class BulkInsertApiIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ProjectMemberMapper members;
  @Autowired ObjectMapper json;

  private void ensureUser(String u) {
    if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u);
  }

  private int[] seed(String owner) throws Exception {
    ensureUser(owner);
    String pj = mvc.perform(post("/api/projects").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"BulkI\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    int pid = json.readTree(pj).get("id").asInt();
    String gj = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf()).with(user(owner))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Panthera\",\"rank\":\"genus\",\"status\":\"ACCEPTED\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return new int[] {pid, json.readTree(gj).get("id").asInt()};
  }

  private String body(int targetId, String mode, String text) throws Exception {
    return json.writeValueAsString(java.util.Map.of("targetId", targetId, "mode", mode, "text", text));
  }

  @Test
  void insertsChildrenNestedAndSynonym() throws Exception {
    int[] s = seed("bulkIns");
    String tree = "Panthera leo [species]\n  =Felis leo\nUncia [subgenus]\n  Uncia uncia [species]\n";
    mvc.perform(post("/api/projects/" + s[0] + "/usages/bulk").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body(s[1], "children", tree)))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.created").value(4))    // leo, Felis leo, Uncia, Uncia uncia
       .andExpect(jsonPath("$.synonymsLinked").value(1));

    // Panthera leo is now present as a species usage. (rank=species disambiguates from the
    // pre-existing genus "Panthera" itself, which pg_trgm's fuzzy `q` filter also matches at
    // ~0.69 similarity since "Panthera leo" is a superstring of "Panthera".)
    mvc.perform(get("/api/projects/" + s[0] + "/usages").param("q", "Panthera leo")
            .param("rank", "species"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.total").value(1));
  }

  @Test
  void synonymyModeLinksToTarget() throws Exception {
    int[] s = seed("bulkIns");
    mvc.perform(post("/api/projects/" + s[0] + "/usages/bulk").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(s[1], "synonyms", "Panthera tigris\nFelis leo\n")))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.created").value(2))
       .andExpect(jsonPath("$.synonymsLinked").value(2));
    // the target genus lists 2 synonyms now
    mvc.perform(get("/api/projects/" + s[0] + "/usages/" + s[1] + "/synonyms"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.length()").value(2));
  }

  @Test
  void overCapIsRejectedAndInsertsNothing() throws Exception {
    int[] s = seed("bulkIns");
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 1001; i++) sb.append("Name").append(i).append(" species\n");
    mvc.perform(post("/api/projects/" + s[0] + "/usages/bulk").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body(s[1], "children", sb.toString())))
       .andExpect(status().isBadRequest());
    mvc.perform(get("/api/projects/" + s[0] + "/usages").param("q", "Name0"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.total").value(0));
  }

  @Test
  void nonEditorForbidden() throws Exception {
    int[] s = seed("bulkIns");
    ensureUser("bulkViewer");
    AppUser v = users.requireByUsernameOrNull("bulkViewer");
    members.upsert(new ProjectMember(s[0], v.getId(), Role.VIEWER.dbValue()));
    mvc.perform(post("/api/projects/" + s[0] + "/usages/bulk").with(csrf()).with(user("bulkViewer"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(s[1], "children", "Panthera leo\n")))
       .andExpect(status().isForbidden());
  }
}
