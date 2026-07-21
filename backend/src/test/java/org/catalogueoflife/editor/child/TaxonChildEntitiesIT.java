package org.catalogueoflife.editor.child;

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

// Covers the 5 taxon-level child entities (vernacular/distribution/media/estimate/property):
// representative CRUD on vernacular, a smoke create on the other four, the ACCEPTED-only create
// guard, and the demote-drop wiring (demoting an accepted usage removes its taxon child entities).
@AutoConfigureMockMvc
@WithMockUser(username = "taxOwner")
class TaxonChildEntitiesIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

  private void ensureUser(String u) {
    if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u);
  }

  // Unique per call: project titles are unique per owner, and this IT (sharing one DB with the whole
  // suite) now creates a project in more than one test method under the same mock user.
  private static final java.util.concurrent.atomic.AtomicInteger SEQ =
      new java.util.concurrent.atomic.AtomicInteger();

  private long createProject() throws Exception {
    String b = mvc.perform(post("/api/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"tax " + SEQ.incrementAndGet() + "\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(b).get("id").asLong();
  }

  private String createUsage(long pid, String name) throws Exception {
    return mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"" + name + "\",\"rank\":\"species\",\"status\":\"accepted\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
  }

  private void post201(long pid, long uid, String resource, String body) throws Exception {
    mvc.perform(post("/api/projects/" + pid + "/usages/" + uid + "/" + resource).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated());
  }

  @Test
  void taxonChildEntitiesLifecycle() throws Exception {
    ensureUser("taxOwner");
    long pid = createProject();
    String aBody = createUsage(pid, "Panthera leo");
    long a = json.readTree(aBody).get("id").asLong();
    int aVersion = json.readTree(aBody).get("version").asInt();
    long b = json.readTree(createUsage(pid, "Panthera tigris")).get("id").asLong();

    // --- vernacular CRUD ---
    String v = mvc.perform(post("/api/projects/" + pid + "/usages/" + a + "/vernaculars").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Lion\",\"language\":\"eng\",\"preferred\":true}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Lion"))
        .andExpect(jsonPath("$.preferred").value(true))
        .andReturn().getResponse().getContentAsString();
    long vId = json.readTree(v).get("id").asLong();
    int vVersion = json.readTree(v).get("version").asInt();

    mvc.perform(get("/api/projects/" + pid + "/usages/" + a + "/vernaculars"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value((int) vId));

    mvc.perform(put("/api/projects/" + pid + "/usages/" + a + "/vernaculars/" + vId).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Asiatic Lion\",\"language\":\"eng\",\"version\":" + vVersion + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Asiatic Lion"));

    // stale version -> 409
    mvc.perform(put("/api/projects/" + pid + "/usages/" + a + "/vernaculars/" + vId).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"x\",\"version\":" + vVersion + "}"))
        .andExpect(status().isConflict());

    // --- smoke create for the other four ---
    post201(pid, a, "distributions",
        "{\"areaId\":\"DE\",\"gazetteer\":\"iso\",\"establishmentMeans\":\"native\"}");
    post201(pid, a, "media", "{\"url\":\"http://x/lion.jpg\",\"type\":\"image\"}");
    post201(pid, a, "estimates", "{\"estimate\":30000,\"type\":\"species count\"}");
    post201(pid, a, "properties", "{\"property\":\"body mass\",\"value\":\"190 kg\"}");

    // --- demote A to a synonym of B: its taxon child entities must be dropped ---
    mvc.perform(post("/api/projects/" + pid + "/usages/" + a + "/demote").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"acceptedId\":" + b + ",\"status\":\"SYNONYM\",\"version\":" + aVersion + "}"))
        .andExpect(status().isOk());

    mvc.perform(get("/api/projects/" + pid + "/usages/" + a + "/vernaculars"))
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(get("/api/projects/" + pid + "/usages/" + a + "/distributions"))
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(get("/api/projects/" + pid + "/usages/" + a + "/estimates"))
        .andExpect(jsonPath("$.length()").value(0));

    // --- accepted-only guard: cannot add a taxon child entity to a non-accepted usage ---
    mvc.perform(post("/api/projects/" + pid + "/usages/" + a + "/vernaculars").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Nope\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unassessedTaxaHoldChildEntitiesAndKeepThemAcrossAccUnassessed() throws Exception {
    ensureUser("taxOwner");
    long pid = createProject();

    // An unassessed ("provisionally accepted") usage may hold taxon-level supplementary data --
    // vernaculars etc. -- just like an accepted taxon (this was rejected before).
    long u = json.readTree(mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Provisia nova\",\"rank\":\"species\",\"status\":\"unassessed\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    post201(pid, u, "vernaculars", "{\"name\":\"Newling\"}");
    mvc.perform(get("/api/projects/" + pid + "/usages/" + u + "/vernaculars"))
        .andExpect(jsonPath("$.length()").value(1));

    // Flipping accepted <-> unassessed keeps the supplementary data (both are taxa); only becoming a
    // synonym/misapplied would drop it. Create an accepted taxon, give it a vernacular, make it
    // unassessed -- the vernacular survives.
    long acc = json.readTree(createUsage(pid, "Panthera onca")).get("id").asLong();
    post201(pid, acc, "vernaculars", "{\"name\":\"Jaguar\"}");
    mvc.perform(put("/api/projects/" + pid + "/usages/" + acc).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Panthera onca\",\"rank\":\"species\",\"status\":\"unassessed\",\"version\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UNASSESSED"));
    mvc.perform(get("/api/projects/" + pid + "/usages/" + acc + "/vernaculars"))
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].name").value("Jaguar"));
  }
}
