package org.catalogueoflife.editor.clb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import life.catalogue.api.model.UsageInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

// ClbImportClient exercised against a mocked CLB HTTP endpoint (Spring's own MockRestServiceServer,
// bound to a RestClient.Builder -- no live network call, no extra mocking dependency like WireMock/
// MockWebServer needed) -- unlike ColMatchIT/ReferenceImportIT, which mock the CLIENT itself as a
// Spring bean (@MockitoBean) to test the SERVICE layer around it, this test's whole point is to
// exercise ClbImportClient's own request-building + Jackson-2-deserialization code, so mocking the
// client itself would test nothing. Pure JUnit -- no Spring context, no Postgres.
class ClbImportClientIT {

  private static final String BASE = "https://mock.clb.test";

  private MockRestServiceServer server;
  private ClbImportClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
    server = MockRestServiceServer.bindTo(builder).build();
    client = new ClbImportClient(builder.build());
  }

  @Test
  void usageInfoDeserializesCannedJson() {
    server.expect(requestTo(BASE + "/dataset/3LXR/taxon/4CGXP/info"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(CANNED_INFO_JSON, MediaType.APPLICATION_JSON));

    UsageInfo info = client.usageInfo("3LXR", "4CGXP");

    assertThat(info.getUsage().getClass().getSimpleName()).isEqualTo("Taxon");
    assertThat(info.getUsage().getName().getScientificName()).isEqualTo("Panthera leo");
    assertThat(info.getUsage().getStatus().name()).isEqualTo("ACCEPTED");
    assertThat(info.getPublishedIn().getId()).isEqualTo("ref-1");
    assertThat(info.getReferences()).containsOnlyKeys("ref-1", "ref-2");
    assertThat(info.getSynonyms().getHomotypic()).hasSize(1);
    assertThat(info.getSynonyms().getHomotypic().get(0).getName().getScientificName()).isEqualTo("Felis leo");
    assertThat(info.getDistributions()).hasSize(1);
    assertThat(info.getVernacularNames()).hasSize(1);
    server.verify();
  }

  @Test
  void usageInfo404MapsToNotFound() {
    server.expect(requestTo(BASE + "/dataset/3LXR/taxon/MISSING/info"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    assertThatThrownBy(() -> client.usageInfo("3LXR", "MISSING"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("CLB taxon not found");
  }

  @Test
  void usageInfo5xxMapsToBadGateway() {
    server.expect(requestTo(BASE + "/dataset/3LXR/taxon/4CGXP/info"))
        .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

    assertThatThrownBy(() -> client.usageInfo("3LXR", "4CGXP"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_GATEWAY));
  }

  @Test
  void searchDatasetsParsesResultHits() {
    server.expect(requestTo(BASE + "/dataset?q=Catalogue&limit=20"))
        .andRespond(withSuccess("""
            {"offset":0,"limit":20,"total":1,"result":[
              {"key":315304,"title":"Species 2000 & ITIS Catalogue of Life","alias":"COL11"}
            ]}
            """, MediaType.APPLICATION_JSON));

    List<ClbImportClient.ClbDatasetHit> hits = client.searchDatasets("Catalogue");
    assertThat(hits).hasSize(1);
    assertThat(hits.get(0).key()).isEqualTo("315304");
    assertThat(hits.get(0).title()).isEqualTo("Species 2000 & ITIS Catalogue of Life");
    assertThat(hits.get(0).alias()).isEqualTo("COL11");
  }

  @Test
  void searchUsagesParsesNestedNameFields() {
    server.expect(requestTo(BASE + "/dataset/3LXR/nameusage?q=Panthera&limit=20&rank=genus"))
        .andRespond(withSuccess("""
            {"offset":0,"limit":20,"total":1,"result":[
              {"id":"6DBT","status":"accepted","name":{"scientificName":"Panthera","rank":"genus"}}
            ]}
            """, MediaType.APPLICATION_JSON));

    List<ClbImportClient.ClbUsageHit> hits = client.searchUsages("3LXR", "Panthera", "genus");
    assertThat(hits).hasSize(1);
    assertThat(hits.get(0).id()).isEqualTo("6DBT");
    assertThat(hits.get(0).scientificName()).isEqualTo("Panthera");
    assertThat(hits.get(0).rank()).isEqualTo("genus");
    assertThat(hits.get(0).status()).isEqualTo("accepted");
  }

  @Test
  void childrenIdsPagesThroughAllResults() {
    server.expect(requestTo(BASE + "/dataset/3LXR/tree/6DBT/children?offset=0&limit=100"))
        .andRespond(withSuccess(childrenPage(0, 100, false), MediaType.APPLICATION_JSON));
    server.expect(requestTo(BASE + "/dataset/3LXR/tree/6DBT/children?offset=100&limit=100"))
        .andRespond(withSuccess(childrenPage(100, 7, true), MediaType.APPLICATION_JSON));

    List<String> ids = client.childrenIds("3LXR", "6DBT");
    assertThat(ids).hasSize(107);
    assertThat(ids.get(0)).isEqualTo("C0");
    assertThat(ids.get(106)).isEqualTo("C106");
    server.verify();
  }

  @Test
  void childrenIdsStopsOnEmptyFirstPage() {
    server.expect(requestTo(BASE + "/dataset/3LXR/tree/6DBT/children?offset=0&limit=100"))
        .andRespond(withSuccess("""
            {"offset":0,"limit":100,"total":0,"result":[],"empty":true,"last":true}
            """, MediaType.APPLICATION_JSON));

    assertThat(client.childrenIds("3LXR", "6DBT")).isEmpty();
    server.verify();
  }

  private static String childrenPage(int start, int count, boolean last) {
    ObjectMapper mapper = new ObjectMapper();
    ArrayNode result = mapper.createArrayNode();
    for (int i = 0; i < count; i++) {
      ObjectNode n = mapper.createObjectNode();
      n.put("id", "C" + (start + i));
      n.put("status", "accepted");
      result.add(n);
    }
    ObjectNode page = mapper.createObjectNode();
    page.put("offset", start);
    page.put("limit", count);
    page.set("result", result);
    page.put("last", last);
    return page.toString();
  }

  // A trimmed, hand-built (but realistically-shaped -- cross-checked against a live
  // GET .../dataset/3LXR/taxon/4CGXP/info response) UsageInfo JSON: an accepted Panthera leo taxon
  // with one homotypic synonym, one distribution, one vernacular name, a publishedIn reference and a
  // second taxonomic reference.
  private static final String CANNED_INFO_JSON = """
      {
        "usage": {
          "id": "4CGXP",
          "name": {
            "id": "N1",
            "scientificName": "Panthera leo",
            "authorship": "(Linnaeus, 1758)",
            "rank": "species",
            "genus": "Panthera",
            "specificEpithet": "leo",
            "publishedInId": "ref-1"
          },
          "status": "accepted",
          "referenceIds": ["ref-2"]
        },
        "publishedIn": {"id": "ref-1", "citation": "Syst. Nat., 10th ed. vol.1 p.41"},
        "synonyms": {
          "homotypic": [
            {"id": "S1", "name": {"id": "N2", "scientificName": "Felis leo", "authorship": "Linnaeus, 1758", "rank": "species"}, "status": "synonym"}
          ],
          "heterotypic": [],
          "misapplied": []
        },
        "distributions": [
          {"area": {"gazetteer": "iso", "id": "DE", "name": "Germany"}, "establishmentMeans": "native"}
        ],
        "vernacularNames": [
          {"name": "Lion", "language": "eng"}
        ],
        "references": {
          "ref-1": {"id": "ref-1", "citation": "Syst. Nat., 10th ed. vol.1 p.41"},
          "ref-2": {"id": "ref-2", "citation": "Some other reference"}
        }
      }
      """;
}
