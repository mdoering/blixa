package org.catalogueoflife.editor.name;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class SearchTsvExportIT extends AbstractPostgresIT {

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
            .content("{\"title\":\"" + title + "\",\"nomCode\":\"botanical\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  private void createUsage(long pid, String name, String authorship, String status) throws Exception {
    mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"" + name + "\",\"authorship\":\"" + authorship
                + "\",\"rank\":\"species\",\"status\":\"" + status + "\"}"))
        .andExpect(status().isCreated());
  }

  private void createReference(long pid, String citation, String doi, String issued) throws Exception {
    mvc.perform(post("/api/projects/" + pid + "/references").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"citation\":\"" + citation + "\",\"doi\":\"" + doi + "\",\"issued\":\""
                + issued + "\"}"))
        .andExpect(status().isCreated());
  }

  @Test
  @WithMockUser(username = "tsvOwner")
  void namesTsvHonoursFiltersAndAuth() throws Exception {
    ensureUser("tsvOwner");
    ensureUser("tsvStranger");
    long pid = createProject("tsvnames");
    createUsage(pid, "Abies alba", "Mill.", "accepted");
    createUsage(pid, "Betula pendula", "Roth", "synonym");

    String body = mvc.perform(get("/api/projects/" + pid + "/usages/export.tsv").param("q", "Abies"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    String[] lines = body.split("\\R");
    assertThat(lines[0].split("\t", -1))
        .containsExactly("id", "scientificName", "authorship", "rank", "status");
    // only the Abies row matches the q filter; Betula is excluded
    assertThat(body).contains("Abies alba\tMill.\tspecies\tACCEPTED");
    assertThat(body).doesNotContain("Betula");

    // no filter -> both rows
    String all = mvc.perform(get("/api/projects/" + pid + "/usages/export.tsv"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    assertThat(all).contains("Abies alba").contains("Betula pendula\tRoth\tspecies\tSYNONYM");

    // a non-member is rejected
    mvc.perform(get("/api/projects/" + pid + "/usages/export.tsv").with(user("tsvStranger")))
        .andExpect(status().is4xxClientError());
  }

  @Test
  @WithMockUser(username = "tsvRefOwner")
  void referencesTsvHonoursFilters() throws Exception {
    ensureUser("tsvRefOwner");
    long pid = createProject("tsvrefs");
    createReference(pid, "Darwin 1859 On the Origin", "10.1/origin", "1859");
    createReference(pid, "Linnaeus 1753 Species Plantarum", "10.2/sp", "1753");

    String body = mvc.perform(
            get("/api/projects/" + pid + "/references/export.tsv").param("q", "Darwin"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    String[] lines = body.split("\\R");
    assertThat(lines[0].split("\t", -1)).containsExactly("id", "citation", "doi", "issued");
    assertThat(body).contains("Darwin 1859 On the Origin\t10.1/origin\t1859");
    assertThat(body).doesNotContain("Linnaeus");
  }
}
