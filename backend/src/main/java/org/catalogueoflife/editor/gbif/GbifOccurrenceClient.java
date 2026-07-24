package org.catalogueoflife.editor.gbif;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// GBIF occurrence search for a COL taxon's type specimens. GBIF ingests COL as a checklist
// (coldp.col.gbif-checklist-key), and its occurrence search accepts the COL taxonID as taxonKey when
// scoped with checklistKey -- so a Blixa usage matched to COL (its col:<id>) queries occurrences
// directly, without the deprecated GBIF backbone. Isolated behind this component (mirrors
// ClbMatchClient/CrossrefClient) so the external HTTP call can be stubbed in tests and the
// response-shape mapping (GbifTypeService) exercised apart from the network.
@Component
public class GbifOccurrenceClient {

  // The nomenclaturally meaningful type statuses (GBIF's TypeStatus vocab minus NotAType); passed as
  // repeated typeStatus filters (GBIF ORs them) so the search returns only genuine type specimens.
  private static final List<String> TYPE_STATUSES = List.of(
      "Holotype", "Lectotype", "Neotype", "Syntype", "Paratype", "Isotype", "Paralectotype",
      "Isolectotype", "Isoneotype", "Epitype", "Allotype", "Cotype", "Topotype", "Type");
  static final int LIMIT = 100;

  private final RestClient http;
  private final ObjectMapper objectMapper;
  private final String checklistKey;

  public GbifOccurrenceClient(ObjectMapper objectMapper,
      @Value("${coldp.gbif.base-url:https://api.gbif.org/v1}") String baseUrl,
      @Value("${coldp.col.gbif-checklist-key:7ddf754f-d193-4cc9-b351-99906754a03b}") String checklistKey) {
    var requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(3));
    requestFactory.setReadTimeout(Duration.ofSeconds(15));
    this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    this.objectMapper = objectMapper;
    this.checklistKey = checklistKey;
  }

  // GET /occurrence/search?checklistKey={col}&taxonKey={colTaxonId}&typeStatus=...&limit=100. Returns
  // the raw response root (results[], count); failures map to 502, exactly like ClbMatchClient.match.
  public JsonNode searchTypeSpecimens(String colTaxonId) {
    var uri = UriComponentsBuilder.fromPath("/occurrence/search")
        .queryParam("checklistKey", checklistKey)
        .queryParam("taxonKey", colTaxonId)
        .queryParam("limit", LIMIT);
    for (String ts : TYPE_STATUSES) {
      uri.queryParam("typeStatus", ts);
    }
    try {
      String body = http.get().uri(uri.encode().build().toUriString()).retrieve().body(String.class);
      return objectMapper.readTree(body);
    } catch (RestClientResponseException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GBIF occurrence search failed");
    } catch (RestClientException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GBIF occurrence search unavailable");
    }
  }
}
