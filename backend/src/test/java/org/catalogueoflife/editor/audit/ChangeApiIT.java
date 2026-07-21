package org.catalogueoflife.editor.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChangeApiIT extends AbstractPostgresIT {

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
            .content("{\"title\":\"" + title + "\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  private long createReference(long pid, String citation) throws Exception {
    String body = mvc.perform(post("/api/projects/" + pid + "/references").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"citation\":\"" + citation + "\",\"title\":\"Original title\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  private long createUsage(long pid, String name, String statusValue, Long parentId) throws Exception {
    String parentJson = parentId == null ? "" : ",\"parentId\":" + parentId;
    String body = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"" + name + "\",\"rank\":\"species\","
                + "\"status\":\"" + statusValue + "\"" + parentJson + "}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  private JsonNode getChanges(long pid, String query) throws Exception {
    String body = mvc.perform(get("/api/projects/" + pid + "/changes" + query))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body);
  }

  @Test
  @WithMockUser(username = "changeOwner")
  void recordsEveryWritePathAndFiltersByEntity() throws Exception {
    ensureUser("changeOwner");
    ensureUser("changeOutsider");
    long pid = createProject("changeproj");
    AppUser owner = users.requireByUsernameOrNull("changeOwner");

    // 1) reference create + update -- exercises ReferenceService.create/update.
    long refId = createReference(pid, "Miller 1768, Gardeners Dictionary");
    mvc.perform(put("/api/projects/" + pid + "/references/" + refId).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"citation\":\"Miller 1768, Gardeners Dictionary\",\"title\":\"Revised title\","
                + "\"version\":0}"))
        .andExpect(status().isOk());

    // 2) a parent usage, then the usage that will be moved -- exercises NameUsageService.create.
    long parentId = createUsage(pid, "Parentus acceptus", "accepted", null);
    long movedId = createUsage(pid, "Movus originalis", "accepted", null);

    // 3) move the usage under the parent -- exercises TreeService.move.
    mvc.perform(put("/api/projects/" + pid + "/tree/usages/" + movedId + "/parent").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"parentId\":" + parentId + ",\"version\":0}"))
        .andExpect(status().isOk());

    // 4) synonym link + unlink -- exercises the synonym_link CREATE/DELETE audit path.
    long synId = createUsage(pid, "Synonymus linkus", "synonym", null);
    mvc.perform(put("/api/projects/" + pid + "/usages/" + synId + "/synonym-of/" + movedId).with(csrf()))
        .andExpect(status().isNoContent());
    mvc.perform(delete("/api/projects/" + pid + "/usages/" + synId + "/synonym-of/" + movedId).with(csrf()))
        .andExpect(status().isNoContent());

    // Full, unfiltered history is newest-first: unlink, link, synonym-usage-create, move,
    // moved-usage-create, parent-usage-create, reference-update, reference-create -- every entry
    // stamped with the acting owner.
    JsonNode all = getChanges(pid, "");
    assertThat(all.size()).isEqualTo(8);
    for (JsonNode n : all) {
      assertThat(n.get("userId").asInt()).isEqualTo(owner.getId());
      assertThat(n.get("username").asString()).isEqualTo("changeOwner");
    }
    assertThat(all.get(0).get("entityType").asString()).isEqualTo("synonym_link");
    assertThat(all.get(0).get("operation").asString()).isEqualTo("DELETE");
    assertThat(all.get(0).get("entityId").asLong()).isEqualTo(synId);
    assertThat(all.get(1).get("entityType").asString()).isEqualTo("synonym_link");
    assertThat(all.get(1).get("operation").asString()).isEqualTo("CREATE");
    assertThat(all.get(1).get("entityId").asLong()).isEqualTo(synId);
    assertThat(all.get(2).get("entityType").asString()).isEqualTo("name_usage");
    assertThat(all.get(2).get("operation").asString()).isEqualTo("CREATE");
    assertThat(all.get(2).get("entityId").asLong()).isEqualTo(synId);
    assertThat(all.get(3).get("entityType").asString()).isEqualTo("name_usage");
    assertThat(all.get(3).get("operation").asString()).isEqualTo("UPDATE");
    assertThat(all.get(3).get("entityId").asLong()).isEqualTo(movedId);
    assertThat(all.get(4).get("entityType").asString()).isEqualTo("name_usage");
    assertThat(all.get(4).get("operation").asString()).isEqualTo("CREATE");
    assertThat(all.get(4).get("entityId").asLong()).isEqualTo(movedId);
    assertThat(all.get(5).get("entityType").asString()).isEqualTo("name_usage");
    assertThat(all.get(5).get("operation").asString()).isEqualTo("CREATE");
    assertThat(all.get(5).get("entityId").asLong()).isEqualTo(parentId);
    assertThat(all.get(6).get("entityType").asString()).isEqualTo("reference");
    assertThat(all.get(6).get("operation").asString()).isEqualTo("UPDATE");
    assertThat(all.get(6).get("entityId").asLong()).isEqualTo(refId);
    assertThat(all.get(7).get("entityType").asString()).isEqualTo("reference");
    assertThat(all.get(7).get("operation").asString()).isEqualTo("CREATE");
    assertThat(all.get(7).get("entityId").asLong()).isEqualTo(refId);

    // the reference-update entry's diff carries the title from/to. This reference is structured
    // (it has a title) and non-manual, so its citation is derived from its fields, not the resent
    // value -- changing the title therefore also regenerates the citation ("Original title." ->
    // "Revised title."), so both fields show in the diff.
    JsonNode refUpdateDiff = json.readTree(all.get(6).get("diff").asString());
    assertThat(refUpdateDiff.get("title").get("from").asString()).isEqualTo("Original title");
    assertThat(refUpdateDiff.get("title").get("to").asString()).isEqualTo("Revised title");
    assertThat(refUpdateDiff.get("citation").get("from").asString()).isEqualTo("Original title.");
    assertThat(refUpdateDiff.get("citation").get("to").asString()).isEqualTo("Revised title.");
    assertThat(refUpdateDiff.size()).isEqualTo(2);

    // the move's diff isolates just the parentId change (version/modified churn is excluded).
    JsonNode moveDiff = json.readTree(all.get(3).get("diff").asString());
    assertThat(moveDiff.get("parentId").get("from").isNull()).isTrue();
    assertThat(moveDiff.get("parentId").get("to").asLong()).isEqualTo(parentId);
    assertThat(moveDiff.size()).isEqualTo(1);

    // the synonym-link create diff carries the accepted id.
    JsonNode linkDiff = json.readTree(all.get(1).get("diff").asString());
    assertThat(linkDiff.get("after").get("acceptedId").asLong()).isEqualTo(movedId);

    // per-entity filter: only the moved usage's own two entries (create + move update).
    JsonNode filtered = getChanges(pid, "?entityType=name_usage&entityId=" + movedId);
    assertThat(filtered.size()).isEqualTo(2);
    assertThat(filtered.get(0).get("operation").asString()).isEqualTo("UPDATE");
    assertThat(filtered.get(1).get("operation").asString()).isEqualTo("CREATE");

    // entityType alone (no entityId) filters by type across all entities of that type: the two
    // reference entries (create + update), and nothing from name_usage or synonym_link.
    JsonNode byType = getChanges(pid, "?entityType=reference");
    assertThat(byType.size()).isEqualTo(2);
    for (JsonNode n : byType) {
      assertThat(n.get("entityType").asString()).isEqualTo("reference");
    }

    // entityId without entityType is a 400, not a silent full-project dump.
    mvc.perform(get("/api/projects/" + pid + "/changes").param("entityId", String.valueOf(movedId)))
        .andExpect(status().isBadRequest());

    // a non-member gets 404, not an empty/leaked result.
    mvc.perform(get("/api/projects/" + pid + "/changes").with(user("changeOutsider")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").exists());
  }
}
