package org.catalogueoflife.editor.mergerecords;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@WithMockUser(username = "mp")
class UsageMergePreviewIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

  private void ensureUser(String u) { if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u); }

  private int createUsage(int pid, String name, String rank, String status, Integer parentId) throws Exception {
    String c = "{\"scientificName\":\"" + name + "\",\"rank\":\"" + rank + "\",\"status\":\"" + status + "\""
        + (parentId == null ? "" : ",\"parentId\":" + parentId) + "}";
    String b = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(c))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(b).get("id").asInt();
  }

  @Test
  void previewReturnsCountsSortedByIdAsc() throws Exception {
    ensureUser("mp");
    String pj = mvc.perform(post("/api/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"MP\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    int pid = json.readTree(pj).get("id").asInt();
    int a = createUsage(pid, "Aus", "genus", "ACCEPTED", null);
    int aSpecies = createUsage(pid, "Aus bus", "species", "ACCEPTED", a);  // child of a
    int b = createUsage(pid, "Aus", "genus", "ACCEPTED", null);            // duplicate genus, no children

    mvc.perform(post("/api/projects/" + pid + "/usages/merge/preview").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(java.util.Map.of("ids", java.util.List.of(b, a)))))
       .andExpect(status().isOk())
       // sorted by id ascending: a (lower id) first
       .andExpect(jsonPath("$[0].id").value(a))
       .andExpect(jsonPath("$[0].counts.children").value(1))  // a has 1 accepted child
       .andExpect(jsonPath("$[1].id").value(b))
       .andExpect(jsonPath("$[1].counts.children").value(0));
  }
}
