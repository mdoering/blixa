package org.catalogueoflife.editor.name;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUserService;
import org.catalogueoflife.editor.validation.IssueMapper;
import org.catalogueoflife.editor.validation.ValidationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// The nomenclatural genus link (genus_id): per-taxon pin + authoritative gender, the fill-missing
// project-wide batch, clear-on-stale on a genus change, and the linked-genus spelling-mismatch rule.
@AutoConfigureMockMvc
@WithMockUser(username = "genusLinkOwner")
class GenusLinkIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;
  @Autowired ValidationService validationService;
  @Autowired IssueMapper issues;

  private void ensureUser(String u) {
    if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u);
  }

  private long createProject(String title) throws Exception {
    String b = mvc.perform(post("/api/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"" + title + "\",\"nomCode\":\"botanical\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(b).get("id").asLong();
  }

  private long create(long pid, String content) throws Exception {
    String b = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(content)).andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(b).get("id").asLong();
  }

  private JsonNode getUsage(long pid, long id) throws Exception {
    return json.readTree(mvc.perform(get("/api/projects/" + pid + "/usages/" + id))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  }

  private void link(long pid, long id, long genusId, int version) throws Exception {
    mvc.perform(put("/api/projects/" + pid + "/usages/" + id + "/genus").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"genusId\":" + genusId + ",\"version\":" + version + "}"))
        .andExpect(status().isOk());
  }

  @Test
  void perTaxonLinkPinsTheGenusAndItsGenderIsAuthoritative() throws Exception {
    ensureUser("genusLinkOwner");
    long pid = createProject("genuslink-per-taxon");
    long abies = create(pid, "{\"scientificName\":\"Abies\",\"rank\":\"genus\",\"status\":\"accepted\",\"gender\":\"FEMININE\"}");
    create(pid, "{\"scientificName\":\"Pinus\",\"rank\":\"genus\",\"status\":\"accepted\",\"gender\":\"NEUTER\"}");
    long alba = create(pid, "{\"scientificName\":\"Abies alba\",\"rank\":\"species\",\"status\":\"accepted\",\"parentId\":" + abies + "}");

    // Unlinked: gender is the name-match fallback (FEMININE from Abies), genusId null.
    JsonNode before = getUsage(pid, alba);
    assertThat(before.get("genusId").isNull()).isTrue();
    assertThat(before.get("genusGender").asString()).isEqualTo("FEMININE");

    // Link it to Abies: authoritative genusId + genusName + gender.
    link(pid, alba, abies, before.get("version").asInt());
    JsonNode after = getUsage(pid, alba);
    assertThat(after.get("genusId").asInt()).isEqualTo((int) abies);
    assertThat(after.get("genusName").asString()).isEqualTo("Abies");
    assertThat(after.get("genusGender").asString()).isEqualTo("FEMININE");
  }

  @Test
  void projectWideBatchLinksMissingOnlyNeverOverriding() throws Exception {
    ensureUser("genusLinkOwner");
    long pid = createProject("genuslink-batch");
    long abies = create(pid, "{\"scientificName\":\"Abies\",\"rank\":\"genus\",\"status\":\"accepted\",\"gender\":\"FEMININE\"}");
    long pinus = create(pid, "{\"scientificName\":\"Pinus\",\"rank\":\"genus\",\"status\":\"accepted\",\"gender\":\"NEUTER\"}");
    long alba = create(pid, "{\"scientificName\":\"Abies alba\",\"rank\":\"species\",\"status\":\"accepted\",\"parentId\":" + abies + "}");
    long syn = create(pid, "{\"scientificName\":\"Pinus alba\",\"rank\":\"species\",\"status\":\"synonym\"}");

    // Manually MIS-link alba to Pinus first: the batch must not overwrite it.
    link(pid, alba, pinus, getUsage(pid, alba).get("version").asInt());

    String body = mvc.perform(post("/api/projects/" + pid + "/link-genera").with(csrf()))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    JsonNode res = json.readTree(body);
    // Only `syn` (Pinus alba) was unlinked -> linked to Pinus. alba keeps its manual (Pinus) link.
    assertThat(res.get("linked").asInt()).isEqualTo(1);
    assertThat(getUsage(pid, syn).get("genusId").asInt()).isEqualTo((int) pinus);
    assertThat(getUsage(pid, alba).get("genusId").asInt()).isEqualTo((int) pinus); // untouched manual link
  }

  @Test
  void batchLinksAnAcceptedNameToItsClassificationGenus() throws Exception {
    ensureUser("genusLinkOwner");
    long pid = createProject("genuslink-accepted-tree");
    long abies = create(pid, "{\"scientificName\":\"Abies\",\"rank\":\"genus\",\"status\":\"accepted\",\"gender\":\"FEMININE\"}");
    long alba = create(pid, "{\"scientificName\":\"Abies alba\",\"rank\":\"species\",\"status\":\"accepted\",\"parentId\":" + abies + "}");

    mvc.perform(post("/api/projects/" + pid + "/link-genera").with(csrf())).andExpect(status().isOk());
    // The accepted name is linked to the very genus usage it sits under (by id), not by name-match.
    assertThat(getUsage(pid, alba).get("genusId").asInt()).isEqualTo((int) abies);
  }

  @Test
  void acceptedGenusLinkRuleFlagsAHomonymMisLink() throws Exception {
    ensureUser("genusLinkOwner");
    long pid = createProject("genuslink-homonym");
    long abies1 = create(pid, "{\"scientificName\":\"Abies\",\"rank\":\"genus\",\"status\":\"accepted\",\"gender\":\"FEMININE\"}");
    long alba = create(pid, "{\"scientificName\":\"Abies alba\",\"rank\":\"species\",\"status\":\"accepted\",\"parentId\":" + abies1 + "}");
    // A homonymous second "Abies" genus (same name, different usage), not alba's classification parent.
    long abies2 = create(pid, "{\"scientificName\":\"Abies\",\"rank\":\"genus\",\"status\":\"accepted\",\"gender\":\"NEUTER\"}");

    // Mis-link alba to the homonym abies2: the NAME matches its token, so the spelling rule stays
    // quiet, but the id-level rule fires because genus_id (abies2) != the classification genus (abies1).
    link(pid, alba, abies2, getUsage(pid, alba).get("version").asInt());
    validationService.revalidateUsage((int) pid, (int) alba);
    var found = issues.findByEntity((int) pid, "name_usage", (int) alba);
    assertThat(found.stream().anyMatch(i -> "accepted_genus_link_not_classification".equals(i.getRule()))).isTrue();
    assertThat(found.stream().anyMatch(i -> "genus_link_spelling_mismatch".equals(i.getRule()))).isFalse();
  }

  @Test
  void updateClearsTheLinkWhenTheGenusTokenChanges() throws Exception {
    ensureUser("genusLinkOwner");
    long pid = createProject("genuslink-stale");
    long abies = create(pid, "{\"scientificName\":\"Abies\",\"rank\":\"genus\",\"status\":\"accepted\",\"gender\":\"FEMININE\"}");
    create(pid, "{\"scientificName\":\"Pinus\",\"rank\":\"genus\",\"status\":\"accepted\",\"gender\":\"NEUTER\"}");
    long alba = create(pid, "{\"scientificName\":\"Abies alba\",\"rank\":\"species\",\"status\":\"accepted\",\"parentId\":" + abies + "}");
    link(pid, alba, abies, getUsage(pid, alba).get("version").asInt());
    int version = getUsage(pid, alba).get("version").asInt();

    // Rename the species' genus token Abies -> Pinus: the stale Abies link must clear.
    mvc.perform(put("/api/projects/" + pid + "/usages/" + alba).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Pinus alba\",\"rank\":\"species\",\"status\":\"accepted\","
                + "\"parentId\":" + abies + ",\"version\":" + version + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.genusId").doesNotExist());
  }

  @Test
  void spellingMismatchRuleFlagsAMisLink() throws Exception {
    ensureUser("genusLinkOwner");
    long pid = createProject("genuslink-mismatch");
    long abies = create(pid, "{\"scientificName\":\"Abies\",\"rank\":\"genus\",\"status\":\"accepted\",\"gender\":\"FEMININE\"}");
    long pinus = create(pid, "{\"scientificName\":\"Pinus\",\"rank\":\"genus\",\"status\":\"accepted\",\"gender\":\"NEUTER\"}");
    long alba = create(pid, "{\"scientificName\":\"Abies alba\",\"rank\":\"species\",\"status\":\"accepted\",\"parentId\":" + abies + "}");

    // Link "Abies alba" to the WRONG genus Pinus -> token "Abies" != linked "Pinus" -> rule fires.
    link(pid, alba, pinus, getUsage(pid, alba).get("version").asInt());
    validationService.revalidateUsage((int) pid, (int) alba);
    assertThat(issues.findByEntity((int) pid, "name_usage", (int) alba).stream()
        .anyMatch(i -> "genus_link_spelling_mismatch".equals(i.getRule()))).isTrue();
  }
}
