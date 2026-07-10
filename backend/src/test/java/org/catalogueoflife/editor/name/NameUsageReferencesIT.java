package org.catalogueoflife.editor.name;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUserService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

// PUT /usages/{id}/references (NameUsageService.setReferences) and POST
// /usages/{id}/web-reference (NameUsageService.addWebReference + WebPageClient). Mirrors
// UsageIdentifiersIT's scaffolding for the CAS write, and ReferenceImportIT's @MockitoBean
// pattern for stubbing the outbound HTTP client so this stays deterministic/offline.
@AutoConfigureMockMvc
@WithMockUser(username = "refsOwner")
class NameUsageReferencesIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

  // Stub the external HTTP fetch so the web-reference test is deterministic and offline -- a real
  // network call must never happen in a Spring IT (see WebPageClientTest for the SSRF unit test
  // that exercises the real client, but only its reject-before-fetch paths).
  @MockitoBean WebPageClient webPageClient;

  private void ensureUser(String u) {
    if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u);
  }

  private long createProject(String title) throws Exception {
    String b = mvc.perform(post("/api/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"" + title + "\",\"nomCode\":\"zoological\"}"))
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

  private long createReference(long pid, String citation) throws Exception {
    String b = mvc.perform(post("/api/projects/" + pid + "/references").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"citation\":\"" + citation + "\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(b).get("id").asLong();
  }

  @Test
  void setReferencesOptimisticLocked() throws Exception {
    ensureUser("refsOwner");
    long pid = createProject("refsproj");
    long u = createUsage(pid, "Aus bus");
    long refA = createReference(pid, "Ref A");
    long refB = createReference(pid, "Ref B");

    // set [refA, refB] -> 200, round-trips on the usage.
    mvc.perform(put("/api/projects/" + pid + "/usages/" + u + "/references").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"referenceIds\":[" + refA + "," + refB + "],\"version\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.referenceId", Matchers.containsInAnyOrder((int) refA, (int) refB)))
        .andExpect(jsonPath("$.version").value(1));

    mvc.perform(get("/api/projects/" + pid + "/usages/" + u))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.referenceId", Matchers.containsInAnyOrder((int) refA, (int) refB)));

    // replace with just refB and the bumped version -> updated, refA gone.
    mvc.perform(put("/api/projects/" + pid + "/usages/" + u + "/references").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"referenceIds\":[" + refB + "],\"version\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.referenceId", Matchers.contains((int) refB)))
        .andExpect(jsonPath("$.version").value(2));

    // stale version (re-send version 0) -> 409.
    mvc.perform(put("/api/projects/" + pid + "/usages/" + u + "/references").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"referenceIds\":[" + refA + "],\"version\":0}"))
        .andExpect(status().isConflict());
  }

  @Test
  void setReferencesRejectsAForeignProjectReferenceId() throws Exception {
    ensureUser("refsOwner");
    long pid = createProject("refsprojA");
    long otherPid = createProject("refsprojB");
    long u = createUsage(pid, "Cus dus");
    long foreignRef = createReference(otherPid, "Foreign ref");

    mvc.perform(put("/api/projects/" + pid + "/usages/" + u + "/references").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"referenceIds\":[" + foreignRef + "],\"version\":0}"))
        .andExpect(status().isBadRequest());

    // a reference id that never existed at all is rejected the same way.
    mvc.perform(put("/api/projects/" + pid + "/usages/" + u + "/references").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"referenceIds\":[999999],\"version\":0}"))
        .andExpect(status().isBadRequest());
  }

  // Regression guard: a null element in referenceIds must 400, not NPE from auto-unboxing a null
  // Integer into a primitive `int` in the per-id validation loop (see
  // NameUsageService.doSetReferences, which iterates as boxed Integer with an explicit null check).
  @Test
  void setReferencesRejectsANullElementInsteadOfServerError() throws Exception {
    ensureUser("refsOwner");
    long pid = createProject("refsprojnull");
    long u = createUsage(pid, "Ius jus");

    mvc.perform(put("/api/projects/" + pid + "/usages/" + u + "/references").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"referenceIds\":[null],\"version\":0}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void addWebReferenceCreatesAWebpageReferenceAndLinksIt() throws Exception {
    ensureUser("refsOwner");
    long pid = createProject("webrefproj");
    long u = createUsage(pid, "Eus fus");

    when(webPageClient.fetchTitle("https://example.org/page")).thenReturn("Example Page");

    String body = mvc.perform(post("/api/projects/" + pid + "/usages/" + u + "/web-reference").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"https://example.org/page\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.referenceId.length()").value(1))
        .andReturn().getResponse().getContentAsString();
    int refId = json.readTree(body).get("referenceId").get(0).asInt();

    mvc.perform(get("/api/projects/" + pid + "/references/" + refId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("webpage"))
        .andExpect(jsonPath("$.title").value("Example Page"))
        .andExpect(jsonPath("$.link").value("https://example.org/page"))
        .andExpect(jsonPath("$.author").value("example.org"))
        .andExpect(jsonPath("$.accessed").isNotEmpty());

    mvc.perform(get("/api/projects/" + pid + "/usages/" + u))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.referenceId", Matchers.contains(refId)));
  }

  @Test
  void addWebReferenceFallsBackToTheUrlWhenNoTitleIsFound() throws Exception {
    ensureUser("refsOwner");
    long pid = createProject("webreffallbackproj");
    long u = createUsage(pid, "Gus hus");

    when(webPageClient.fetchTitle("https://example.org/no-title")).thenReturn(null);

    String body = mvc.perform(post("/api/projects/" + pid + "/usages/" + u + "/web-reference").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"https://example.org/no-title\"}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    int refId = json.readTree(body).get("referenceId").get(0).asInt();

    mvc.perform(get("/api/projects/" + pid + "/references/" + refId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("https://example.org/no-title"));
  }
}
