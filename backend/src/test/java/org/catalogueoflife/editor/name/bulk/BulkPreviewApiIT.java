package org.catalogueoflife.editor.name.bulk;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
@WithMockUser(username = "bulkPrev")
class BulkPreviewApiIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

  private void ensureUser(String u) {
    if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u);
  }

  // Creates a project + one accepted genus, returns [projectId, genusId].
  private int[] seed() throws Exception {
    ensureUser("bulkPrev");
    String pj = mvc.perform(post("/api/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"BulkP\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    int pid = json.readTree(pj).get("id").asInt();
    String gj = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Panthera\",\"rank\":\"genus\",\"status\":\"ACCEPTED\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return new int[] {pid, json.readTree(gj).get("id").asInt()};
  }

  private String body(int targetId, String mode, String text) throws Exception {
    return json.writeValueAsString(java.util.Map.of("targetId", targetId, "mode", mode, "text", text));
  }

  @Test
  void previewPlainListAsChildren() throws Exception {
    int[] s = seed();
    mvc.perform(post("/api/projects/" + s[0] + "/usages/bulk/preview").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(s[1], "children", "Panthera leo [species]\nPanthera onca [species]\n")))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.valid").value(true))
       .andExpect(jsonPath("$.total").value(2))
       .andExpect(jsonPath("$.accepted").value(2))
       .andExpect(jsonPath("$.synonyms").value(0))
       .andExpect(jsonPath("$.nodes[0].name").value("Panthera leo"))
       .andExpect(jsonPath("$.nodes[0].rank").value("species"))
       .andExpect(jsonPath("$.nodes[0].status").value("ACCEPTED"));
  }

  @Test
  void previewInfersRankForBareBinomial() throws Exception {
    int[] s = seed();
    mvc.perform(post("/api/projects/" + s[0] + "/usages/bulk/preview").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(s[1], "children", "Panthera leo\n")))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.nodes[0].rank").value("species"));
  }

  @Test
  void previewIndentedWithSynonym() throws Exception {
    int[] s = seed();
    String tree = "Panthera leo [species]\n  =Felis leo\n";
    mvc.perform(post("/api/projects/" + s[0] + "/usages/bulk/preview").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body(s[1], "children", tree)))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.total").value(2))
       .andExpect(jsonPath("$.accepted").value(1))
       .andExpect(jsonPath("$.synonyms").value(1))
       .andExpect(jsonPath("$.nodes[0].synonyms[0].name").value("Felis leo"))
       .andExpect(jsonPath("$.nodes[0].synonyms[0].status").value("SYNONYM"));
  }

  @Test
  void previewBadIndentationIsInvalid() throws Exception {
    int[] s = seed();
    // 4-space over-indent jump is rejected by the parser
    mvc.perform(post("/api/projects/" + s[0] + "/usages/bulk/preview").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(s[1], "children", "Aaa [genus]\n    Bbb ccc [species]\n")))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.valid").value(false))
       .andExpect(jsonPath("$.error").isNotEmpty());
  }

  @Test
  void previewSynonymyModeRejectsIndentation() throws Exception {
    int[] s = seed();
    mvc.perform(post("/api/projects/" + s[0] + "/usages/bulk/preview").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(s[1], "synonyms", "Panthera leo\n  Panthera onca\n")))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.valid").value(false))
       .andExpect(jsonPath("$.error").isNotEmpty());
  }

  @Test
  void previewWritesNothing() throws Exception {
    int[] s = seed();
    mvc.perform(post("/api/projects/" + s[0] + "/usages/bulk/preview").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(s[1], "children", "Panthera leo [species]\nPanthera onca [species]\n")))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.total").value(2));
    // A preview must not create anything: the project still has exactly the one seeded genus.
    mvc.perform(get("/api/projects/" + s[0] + "/usages"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.total").value(1));
  }
}
