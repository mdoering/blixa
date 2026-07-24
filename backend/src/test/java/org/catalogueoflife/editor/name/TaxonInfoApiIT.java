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

// End-to-end (real Postgres) coverage for the Biology tab's narrow taxon_info write path
// (PUT /usages/{id}/taxon-info): round-trip of extinct/environment/temporal range, clearing them,
// a stale-version 409, and that the write leaves the usage's name fields untouched.
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "taxonInfoOwner")
class TaxonInfoApiIT extends AbstractPostgresIT {

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

  private JsonNode createUsage(long pid, String name) throws Exception {
    String body = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"" + name + "\",\"rank\":\"species\",\"status\":\"accepted\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(body);
  }

  private JsonNode getUsage(long pid, long id) throws Exception {
    return json.readTree(mvc.perform(get("/api/projects/" + pid + "/usages/" + id))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  }

  @Test
  void roundTripsExtinctEnvironmentAndTemporalRange() throws Exception {
    ensureUser("taxonInfoOwner");
    long pid = createProject("taxoninfoproj");
    long id = createUsage(pid, "Ammonites priscus").get("id").asLong();

    String body = mvc.perform(put("/api/projects/" + pid + "/usages/" + id + "/taxon-info").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"extinct\":true,\"environment\":[\"MARINE\"],"
                + "\"temporalRangeStart\":\"Jurassic\",\"temporalRangeEnd\":\"Cretaceous\",\"version\":0}"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    JsonNode after = json.readTree(body);
    assertThat(after.get("extinct").asBoolean()).isTrue();
    assertThat(after.get("environment").get(0).asString()).isEqualTo("MARINE");
    assertThat(after.get("temporalRangeStart").asString()).isEqualTo("Jurassic");
    assertThat(after.get("temporalRangeEnd").asString()).isEqualTo("Cretaceous");
    assertThat(after.get("version").asInt()).isEqualTo(1);

    // A fresh GET reflects the persisted taxon_info.
    JsonNode fetched = getUsage(pid, id);
    assertThat(fetched.get("extinct").asBoolean()).isTrue();
    assertThat(fetched.get("temporalRangeStart").asString()).isEqualTo("Jurassic");
  }

  @Test
  void clearingAllFieldsDropsTheTaxonInfo() throws Exception {
    ensureUser("taxonInfoOwner");
    long pid = createProject("taxoninfoclearproj");
    long id = createUsage(pid, "Ammonites priscus").get("id").asLong();

    // set, then clear everything (null-ish) at version 1
    mvc.perform(put("/api/projects/" + pid + "/usages/" + id + "/taxon-info").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"extinct\":true,\"environment\":[\"MARINE\"],\"version\":0}"))
        .andExpect(status().isOk());
    String body = mvc.perform(put("/api/projects/" + pid + "/usages/" + id + "/taxon-info").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    JsonNode after = json.readTree(body);
    assertThat(after.get("extinct").isNull()).isTrue();
    assertThat(after.get("environment") == null || after.get("environment").isNull()
        || after.get("environment").isEmpty()).isTrue();
    assertThat(after.get("temporalRangeStart").isNull()).isTrue();
  }

  @Test
  void staleVersionConflicts() throws Exception {
    ensureUser("taxonInfoOwner");
    long pid = createProject("taxoninfoconflictproj");
    long id = createUsage(pid, "Ammonites priscus").get("id").asLong();

    // first write moves version 0 -> 1
    mvc.perform(put("/api/projects/" + pid + "/usages/" + id + "/taxon-info").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"extinct\":true,\"version\":0}"))
        .andExpect(status().isOk());
    // a second write still claiming version 0 must 409
    mvc.perform(put("/api/projects/" + pid + "/usages/" + id + "/taxon-info").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"extinct\":false,\"version\":0}"))
        .andExpect(status().isConflict());
  }

  @Test
  void doesNotAlterNameFields() throws Exception {
    ensureUser("taxonInfoOwner");
    long pid = createProject("taxoninfonamesproj");
    JsonNode created = createUsage(pid, "Ammonites priscus");
    long id = created.get("id").asLong();

    mvc.perform(put("/api/projects/" + pid + "/usages/" + id + "/taxon-info").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"extinct\":true,\"environment\":[\"MARINE\"],\"version\":0}"))
        .andExpect(status().isOk());

    JsonNode after = getUsage(pid, id);
    assertThat(after.get("scientificName").asString()).isEqualTo("Ammonites priscus");
    assertThat(after.get("rank").asString()).isEqualTo("species");
    assertThat(after.get("status").asString()).isEqualTo("ACCEPTED");
    assertThat(after.get("specificEpithet").asString()).isEqualTo("priscus");
  }
}
