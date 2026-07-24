package org.catalogueoflife.editor.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.name.ReferenceMapper;
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

// End-to-end (real Postgres) coverage for the two rules added on 2026-07-24: infraspecific_missing_species
// (exercises NameUsageMapper.hasSpeciesAncestor's recursive CTE) and dangling_reference (exercises
// ReferenceMapper.existingIds + the reference_id[] dangling computation in ValidationService.buildContext).
// Data is seeded through the real API, then ValidationService.revalidateUsage is called directly and the
// resulting issues asserted -- same shape as ValidationReconcileIT.
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "newRulesOwner")
class NewValidationRulesIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;
  @Autowired ValidationService validationService;
  @Autowired IssueMapper issueMapper;
  @Autowired ReferenceMapper referenceMapper;

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

  private boolean hasIssue(long pid, long usageId, String rule) {
    return issueMapper.findByEntity((int) pid, "name_usage", (int) usageId).stream()
        .anyMatch(i -> rule.equals(i.getRule()));
  }

  @Test
  void infraspecificMissingSpeciesFlagsASubspeciesParentedUnderAGenus() throws Exception {
    ensureUser("newRulesOwner");
    long pid = createProject("infraproj");
    long genus = createUsage(pid, "Panthera", "genus", null);

    // subspecies straight under the genus -- no species in between -> flagged.
    long badSubsp = createUsage(pid, "Panthera leo persica", "subspecies", genus);
    validationService.revalidateUsage((int) pid, (int) badSubsp);
    assertThat(hasIssue(pid, badSubsp, "infraspecific_missing_species")).isTrue();

    // subspecies properly under its species -> not flagged.
    long species = createUsage(pid, "Panthera leo", "species", genus);
    long goodSubsp = createUsage(pid, "Panthera leo goojratensis", "subspecies", species);
    validationService.revalidateUsage((int) pid, (int) goodSubsp);
    assertThat(hasIssue(pid, goodSubsp, "infraspecific_missing_species")).isFalse();
  }

  @Test
  void danglingReferenceFlagsAUsageWhoseTaxonomicReferenceWasDeleted() throws Exception {
    ensureUser("newRulesOwner");
    long pid = createProject("danglingproj");

    String refBody = mvc.perform(post("/api/projects/" + pid + "/references").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"citation\":\"Smith 1900\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    long refId = json.readTree(refBody).get("id").asLong();

    long usageId = createUsage(pid, "Aus bus", "species", null);
    // Link the usage's taxonomic reference_id[] to the reference (the API validates it exists).
    mvc.perform(put("/api/projects/" + pid + "/usages/" + usageId + "/references").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"referenceIds\":[" + refId + "],\"version\":0}"))
        .andExpect(status().isOk());

    // While the reference exists, nothing dangles.
    validationService.revalidateUsage((int) pid, (int) usageId);
    assertThat(hasIssue(pid, usageId, "dangling_reference")).isFalse();

    // Simulate an external delete / import that left the reference_id[] pointer behind: drop the
    // reference row via the raw mapper, bypassing ReferenceService's reference_id[] cleanup.
    referenceMapper.delete((int) pid, (int) refId);

    validationService.revalidateUsage((int) pid, (int) usageId);
    assertThat(hasIssue(pid, usageId, "dangling_reference")).isTrue();
  }
}
