package org.catalogueoflife.editor.name.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
  private static final java.util.concurrent.atomic.AtomicInteger SEQ =
      new java.util.concurrent.atomic.AtomicInteger();

  private int[] seed() throws Exception {
    ensureUser("bulkPrev");
    String pj = mvc.perform(post("/api/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"BulkP " + SEQ.incrementAndGet() + "\",\"nomCode\":\"zoological\"}"))
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
  void previewChildrenFlagsCanonicalDuplicateDespiteAuthorship() throws Exception {
    int[] s = seed();
    // Seed an accepted child "Panthera leo" (no authorship stored) directly under the genus.
    mvc.perform(post("/api/projects/" + s[0] + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Panthera leo\",\"rank\":\"species\","
                + "\"status\":\"ACCEPTED\",\"parentId\":" + s[1] + "}"))
        .andExpect(status().isCreated());
    // The preview input carries authorship -- canonical matching must still flag it as a duplicate.
    mvc.perform(post("/api/projects/" + s[0] + "/usages/bulk/preview").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(s[1], "children", "Panthera leo (Linnaeus, 1758) [species]\n")))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.valid").value(true))
       .andExpect(jsonPath("$.duplicates").value(1))
       .andExpect(jsonPath("$.nodes[0].duplicate").value(true));
  }

  @Test
  void previewChildrenDoesNotFlagDistinctName() throws Exception {
    int[] s = seed();
    mvc.perform(post("/api/projects/" + s[0] + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Panthera leo\",\"rank\":\"species\","
                + "\"status\":\"ACCEPTED\",\"parentId\":" + s[1] + "}"))
        .andExpect(status().isCreated());
    mvc.perform(post("/api/projects/" + s[0] + "/usages/bulk/preview").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(s[1], "children", "Panthera onca [species]\n")))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.valid").value(true))
       .andExpect(jsonPath("$.duplicates").value(0))
       .andExpect(jsonPath("$.nodes[0].duplicate").value(false));
  }

  // Regression test for the under-scoped join in NameUsageMapper.findSynonymsOfAccepted: id is
  // app-allocated PER PROJECT (see IdSeqMapper), so nu.id alone is not globally unique. Without
  // an explicit nu.project_id predicate, the join can pull in name_usage rows from an unrelated
  // project that happen to share the numeric id, leaking them into synonym-mode duplicate
  // detection. This forces a real cross-project id collision and asserts P2's usage is invisible
  // to P1's preview.
  @Test
  void previewSynonymsModeIsolatedToOwningProject() throws Exception {
    ensureUser("bulkPrev");

    // --- Project P2 first: two throwaway usages, then a distinctively-named one at id 3. ---
    String p2j = mvc.perform(post("/api/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"BulkP2\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    int p2 = json.readTree(p2j).get("id").asInt();

    mvc.perform(post("/api/projects/" + p2 + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Aaa\",\"rank\":\"genus\",\"status\":\"ACCEPTED\"}"))
        .andExpect(status().isCreated());
    mvc.perform(post("/api/projects/" + p2 + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Bbb bbb\",\"rank\":\"species\",\"status\":\"ACCEPTED\"}"))
        .andExpect(status().isCreated());
    String zj = mvc.perform(post("/api/projects/" + p2 + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Zzz zzz\",\"rank\":\"species\",\"status\":\"ACCEPTED\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    int p2ZzzId = json.readTree(zj).get("id").asInt();

    // --- Project P1: accepted genus (seed), accepted target under it, and a synonym linked to
    // the target. Same number of prior creates (genus, target) as P2's (Aaa, Bbb bbb) means the
    // synonym lands on P1's 3rd allocated id, same as P2's "Zzz zzz".
    int[] s = seed();
    int p1 = s[0];
    int genusId = s[1];
    String tj = mvc.perform(post("/api/projects/" + p1 + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Panthera onca\",\"rank\":\"species\","
                + "\"status\":\"ACCEPTED\",\"parentId\":" + genusId + "}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    int targetId = json.readTree(tj).get("id").asInt();
    String synJ = mvc.perform(post("/api/projects/" + p1 + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Felis leo\",\"rank\":\"species\",\"status\":\"SYNONYM\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    int p1SynonymId = json.readTree(synJ).get("id").asInt();
    mvc.perform(put("/api/projects/" + p1 + "/usages/" + p1SynonymId + "/synonym-of/" + targetId)
            .with(csrf()))
        .andExpect(status().isNoContent());

    // Self-verifying precondition: the cross-project id collision this test relies on is real.
    assertThat(p1SynonymId).isEqualTo(p2ZzzId);

    // Canonical match against P1's real synonym "Felis leo" -- authorship in the input is ignored.
    mvc.perform(post("/api/projects/" + p1 + "/usages/bulk/preview").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(targetId, "synonyms", "Felis leo (Linnaeus, 1758)\n")))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.duplicates").value(1))
       .andExpect(jsonPath("$.nodes[0].duplicate").value(true));

    // Isolation: P2's "Zzz zzz" shares P1's synonym id but lives in a different project -- it must
    // NOT be treated as an existing synonym of P1's target.
    mvc.perform(post("/api/projects/" + p1 + "/usages/bulk/preview").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(targetId, "synonyms", "Zzz zzz\n")))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.nodes[0].duplicate").value(false));
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
