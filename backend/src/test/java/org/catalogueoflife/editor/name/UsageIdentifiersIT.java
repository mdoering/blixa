package org.catalogueoflife.editor.name;

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

// PUT /usages/{id}/identifiers (NameUsageService.setIdentifiers): a full-replace, optimistic-locked
// write of name_usage.alternative_id -- the path a later "match to COL" feature uses to persist
// col:<id> (see NameUsageService.mergeColId). Mirrors TypeMaterialIT's createProject/createUsage
// scaffolding.
@AutoConfigureMockMvc
@WithMockUser(username = "identifiersOwner")
class UsageIdentifiersIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

  private void ensureUser(String u) {
    if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u);
  }

  private long createProject() throws Exception {
    String b = mvc.perform(post("/api/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"identifiers\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(b).get("id").asLong();
  }

  private long createUsage(long pid, String name) throws Exception {
    String b = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"" + name + "\",\"rank\":\"species\",\"status\":\"accepted\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(b).get("id").asLong();
  }

  @Test
  void putIdentifiersOptimisticLocked() throws Exception {
    ensureUser("identifiersOwner");
    long pid = createProject();
    long u = createUsage(pid, "Aus bus");

    // initial write: version 0 (fresh usage) -> 200, alternativeId round-trips.
    mvc.perform(put("/api/projects/" + pid + "/usages/" + u + "/identifiers").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"alternativeId\":[\"col:6W3C4\",\"tsn:1\"],\"version\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.alternativeId", org.hamcrest.Matchers.containsInAnyOrder("col:6W3C4", "tsn:1")))
        .andExpect(jsonPath("$.version").value(1));

    // GET reflects the persisted alternativeId.
    mvc.perform(get("/api/projects/" + pid + "/usages/" + u))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.alternativeId", org.hamcrest.Matchers.containsInAnyOrder("col:6W3C4", "tsn:1")));

    // replace: send a new array (full replace, not merge) with the bumped version -> col:XYZ present,
    // tsn:1 still present (caller carried it over), col:6W3C4 gone (full replace of the field).
    mvc.perform(put("/api/projects/" + pid + "/usages/" + u + "/identifiers").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"alternativeId\":[\"col:XYZ\",\"tsn:1\"],\"version\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.alternativeId", org.hamcrest.Matchers.containsInAnyOrder("col:XYZ", "tsn:1")))
        .andExpect(jsonPath("$.version").value(2));

    // stale version (re-send the original version 0) -> 409, matching sibling updates.
    mvc.perform(put("/api/projects/" + pid + "/usages/" + u + "/identifiers").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"alternativeId\":[\"col:STALE\"],\"version\":0}"))
        .andExpect(status().isConflict());
  }

  // Regression guard: version omitted entirely (null, not 0) must still degrade to the standard
  // stale-version 409 -- NOT throw an NPE from auto-unboxing a null Integer into the mapper's
  // primitive int parameter (see NameUsageMapper.updateAlternativeId; fixed to take a boxed
  // Integer, matching every other CAS write path in this codebase).
  @Test
  void putIdentifiersMissingVersionIsConflictNotServerError() throws Exception {
    ensureUser("identifiersOwner");
    long pid = createProject();
    long u = createUsage(pid, "Cus dus");

    mvc.perform(put("/api/projects/" + pid + "/usages/" + u + "/identifiers").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"alternativeId\":[\"col:AAA\"]}"))
        .andExpect(status().isConflict());
  }
}
