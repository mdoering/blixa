package org.catalogueoflife.editor.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

// End-to-end (real Postgres) coverage for the two query-backed rules in the "deferred issue checks"
// batch (2026-07-24): duplicate_child_records (exercises NameUsageMapper.duplicateChildTypes' per-usage
// GROUP BY ... HAVING across the child tables) and synonym_rank_differs (exercises
// NameUsageMapper.synonymRankDiffers' synonym_accepted join). The two pure-in-memory rules of the same
// batch (superfluous_authorship, suspicious_name_characters) need no DB and are covered in RuleTests.
// Data is seeded through the real API, then ValidationService.revalidateUsage is called directly and
// the resulting issues asserted -- same shape as NewValidationRulesIT.
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "deferredChecksOwner")
class DeferredIssueChecksIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;
  @Autowired ValidationService validationService;
  @Autowired IssueMapper issueMapper;

  private void ensureUser(String u) {
    if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u);
  }

  private long createProject(String title) throws Exception {
    String body = mvc.perform(post("/api/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"" + title + "\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  private long createUsage(long pid, String name, String rank, Long parentId) throws Exception {
    String parentJson = parentId == null ? "" : ",\"parentId\":" + parentId;
    String body = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"" + name + "\",\"rank\":\"" + rank
                + "\",\"status\":\"accepted\"" + parentJson + "}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  private void addDistribution(long pid, long usageId, String area) throws Exception {
    mvc.perform(post("/api/projects/" + pid + "/usages/" + usageId + "/distributions").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"area\":\"" + area + "\"}"))
        .andExpect(status().isCreated());
  }

  private boolean hasIssue(long pid, long usageId, String rule) {
    return issueMapper.findByEntity((int) pid, "name_usage", (int) usageId).stream()
        .anyMatch(i -> rule.equals(i.getRule()));
  }

  @Test
  void duplicateChildRecordsFlagsTwoDistributionsForTheSameArea() throws Exception {
    ensureUser("deferredChecksOwner");
    long pid = createProject("dupchildproj");

    // Two distributions naming the same area -> a duplicate group -> flagged.
    long dup = createUsage(pid, "Aus bus", "species", null);
    addDistribution(pid, dup, "Germany");
    addDistribution(pid, dup, "Germany");
    validationService.revalidateUsage((int) pid, (int) dup);
    assertThat(hasIssue(pid, dup, "duplicate_child_records")).isTrue();

    // A single distribution for a different area -> no duplicate -> not flagged.
    long clean = createUsage(pid, "Aus cus", "species", null);
    addDistribution(pid, clean, "France");
    validationService.revalidateUsage((int) pid, (int) clean);
    assertThat(hasIssue(pid, clean, "duplicate_child_records")).isFalse();
  }

  @Test
  void synonymRankDiffersFlagsASynonymOfADifferentRank() throws Exception {
    ensureUser("deferredChecksOwner");
    long pid = createProject("synrankproj");
    long species = createUsage(pid, "Panthera leo", "species", null);

    // Subspecies synonymised under a species -> ranks differ -> flagged.
    long badSyn = createUsage(pid, "Panthera leo persica", "subspecies", null);
    mvc.perform(put("/api/projects/" + pid + "/usages/" + badSyn + "/synonym-of/" + species).with(csrf()))
        .andExpect(status().isNoContent());
    validationService.revalidateUsage((int) pid, (int) badSyn);
    assertThat(hasIssue(pid, badSyn, "synonym_rank_differs")).isTrue();

    // Species synonymised under a species -> same rank -> not flagged.
    long goodSyn = createUsage(pid, "Felis leo", "species", null);
    mvc.perform(put("/api/projects/" + pid + "/usages/" + goodSyn + "/synonym-of/" + species).with(csrf()))
        .andExpect(status().isNoContent());
    validationService.revalidateUsage((int) pid, (int) goodSyn);
    assertThat(hasIssue(pid, goodSyn, "synonym_rank_differs")).isFalse();
  }
}
