package org.catalogueoflife.editor.gbif;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.name.ClbMatchClient;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// GET /usages/{id}/gbif-types (GbifTypeService): resolves a usage to COL and maps GBIF type-specimen
// occurrences onto TypeMaterial candidates. The external GBIF + CLB calls are mocked (never a live
// request); this exercises COL-id resolution (from a stored col: id vs a name-match), the occurrence
// -> candidate mapping, and already-imported deduping.
@AutoConfigureMockMvc
@WithMockUser(username = "gbifTypesOwner")
class GbifTypesIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

  @MockitoBean GbifOccurrenceClient gbif;
  @MockitoBean ClbMatchClient clb;

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

  private JsonNode gbifTypes(long pid, long usageId) throws Exception {
    return json.readTree(mvc.perform(get("/api/projects/" + pid + "/usages/" + usageId + "/gbif-types"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  }

  @Test
  void mapsTypeSpecimensFromAStoredColIdAndFlagsAlreadyImported() throws Exception {
    ensureUser("gbifTypesOwner");
    long pid = createProject("gbiftypesproj");
    long usageId = createUsage(pid, "Panthera leo");

    // Stamp a col: id so resolveColId short-circuits without a CLB call.
    mvc.perform(put("/api/projects/" + pid + "/usages/" + usageId + "/identifiers").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"alternativeId\":[\"col:TESTCOL\"],\"version\":0}"))
        .andExpect(status().isOk());

    // Pre-import a TypeMaterial for the first occurrence, so it comes back alreadyImported.
    mvc.perform(post("/api/projects/" + pid + "/usages/" + usageId + "/type-material").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"holotype\",\"occurrenceId\":\"urn:cat:AMNH:M-1\"}"))
        .andExpect(status().isCreated());

    when(gbif.searchTypeSpecimens("TESTCOL")).thenReturn(json.readTree("""
        {
          "count": 2,
          "results": [
            {"key": 111, "typeStatus": "Holotype", "institutionCode": "AMNH", "catalogNumber": "M-1",
             "occurrenceID": "urn:cat:AMNH:M-1", "locality": "Ituri", "country": "Congo",
             "recordedBy": "Lang", "eventDate": "1912-04-18", "sex": "Male",
             "decimalLatitude": 1.5, "decimalLongitude": 20.0, "scientificName": "Panthera leo"},
            {"key": 222, "typeStatus": ["Paratype"], "institutionCode": "BMNH", "catalogNumber": "M-2",
             "occurrenceID": "urn:cat:BMNH:M-2", "scientificName": "Panthera leo azandica"}
          ]
        }
        """));

    JsonNode resp = gbifTypes(pid, usageId);
    assertThat(resp.get("colId").asString()).isEqualTo("TESTCOL");
    assertThat(resp.get("truncated").asBoolean()).isFalse();
    JsonNode cands = resp.get("candidates");
    assertThat(cands.size()).isEqualTo(2);

    JsonNode c0 = cands.get(0);
    assertThat(c0.get("status").asString()).isEqualTo("Holotype");
    assertThat(c0.get("institutionCode").asString()).isEqualTo("AMNH");
    assertThat(c0.get("occurrenceId").asString()).isEqualTo("urn:cat:AMNH:M-1");
    assertThat(c0.get("link").asString()).isEqualTo("https://www.gbif.org/occurrence/111");
    assertThat(c0.get("collector").asString()).isEqualTo("Lang");
    assertThat(c0.get("date").asString()).isEqualTo("1912-04-18");
    assertThat(c0.get("latitude").asDouble()).isEqualTo(1.5);
    assertThat(c0.get("citation").asString()).isEqualTo("Panthera leo");
    assertThat(c0.get("alreadyImported").asBoolean()).isTrue();

    JsonNode c1 = cands.get(1);
    assertThat(c1.get("status").asString()).isEqualTo("Paratype"); // array typeStatus -> first value
    assertThat(c1.get("alreadyImported").asBoolean()).isFalse();
  }

  @Test
  void reportsNoColMatchWhenTheNameCannotBeResolved() throws Exception {
    ensureUser("gbifTypesOwner");
    long pid = createProject("gbifnomatchproj");
    long usageId = createUsage(pid, "Nomatchia obscura");

    // No stored col: id -> resolveColId falls back to a CLB name-match, which returns type NONE.
    when(clb.defaultColDataset()).thenReturn("3LXR");
    when(clb.match(anyString(), anyString(), any(), any(), any(), anyList()))
        .thenReturn(json.readTree("{\"type\":\"NONE\"}"));

    JsonNode resp = gbifTypes(pid, usageId);
    assertThat(resp.get("colId").isNull()).isTrue();
    assertThat(resp.get("candidates").size()).isZero();
  }
}
