package org.catalogueoflife.editor.name;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// GET /usages/{id}/counts -- the per-tab record counts shown in the TaxonDetail tab labels.
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "usageCountsOwner")
class UsageCountsIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

  private void ensureUser(String u) {
    if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u);
  }

  private long createProject(String title) throws Exception {
    String body = mvc.perform(post("/api/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"" + title + "\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  private long createUsage(long pid, String name, String status) throws Exception {
    String body = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"" + name + "\",\"rank\":\"species\",\"status\":\"" + status + "\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  private void createChild(long pid, long usageId, String resource, String content) throws Exception {
    mvc.perform(post("/api/projects/" + pid + "/usages/" + usageId + "/" + resource).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(content))
        .andExpect(status().isCreated());
  }

  private JsonNode counts(long pid, long id) throws Exception {
    return json.readTree(mvc.perform(get("/api/projects/" + pid + "/usages/" + id + "/counts"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  }

  @Test
  void countsEachTabsRecords() throws Exception {
    ensureUser("usageCountsOwner");
    long pid = createProject("usagecountsproj");
    long acc = createUsage(pid, "Aus bus", "accepted");

    JsonNode empty = counts(pid, acc);
    assertThat(empty.get("synonyms").asInt()).isZero();
    assertThat(empty.get("vernaculars").asInt()).isZero();
    assertThat(empty.get("distributions").asInt()).isZero();

    long syn1 = createUsage(pid, "Aus cus", "synonym");
    long syn2 = createUsage(pid, "Aus dus", "synonym");
    for (long syn : new long[] {syn1, syn2}) {
      mvc.perform(put("/api/projects/" + pid + "/usages/" + syn + "/synonym-of/" + acc).with(csrf()))
          .andExpect(status().isNoContent());
    }
    createChild(pid, acc, "vernaculars", "{\"name\":\"Blue bug\",\"language\":\"eng\"}");
    createChild(pid, acc, "vernaculars", "{\"name\":\"Blauwanze\",\"language\":\"deu\"}");
    createChild(pid, acc, "distributions", "{\"area\":\"Europe\"}");

    JsonNode c = counts(pid, acc);
    assertThat(c.get("synonyms").asInt()).isEqualTo(2);
    assertThat(c.get("vernaculars").asInt()).isEqualTo(2);
    assertThat(c.get("distributions").asInt()).isEqualTo(1);
    assertThat(c.get("media").asInt()).isZero();
    assertThat(c.get("estimates").asInt()).isZero();
    assertThat(c.get("properties").asInt()).isZero();
    assertThat(c.get("typeMaterial").asInt()).isZero();
    assertThat(c.get("nameRelations").asInt()).isZero();
    assertThat(c.get("discussions").asInt()).isZero();
    assertThat(c.has("issues")).isTrue();
  }

  @Test
  void unknownUsageIs404() throws Exception {
    ensureUser("usageCountsOwner");
    long pid = createProject("usagecountsproj404");
    mvc.perform(get("/api/projects/" + pid + "/usages/999999/counts")).andExpect(status().isNotFound());
  }
}
