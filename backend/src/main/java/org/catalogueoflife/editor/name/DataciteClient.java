package org.catalogueoflife.editor.name;

import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Server-side DataCite lookups for DOI resolution, used as a fallback when a DOI isn't registered
// with Crossref (datasets, software, and other DataCite-only DOIs commonly aren't). Isolated behind
// this component so the external HTTP call can be @MockitoBean'd in tests and the mapping
// (RefMapping.fromDatacite) tested apart -- mirrors CrossrefClient.
@Component
public class DataciteClient {

  private final RestClient http;
  private final ObjectMapper objectMapper;

  // RestClient.builder() (the static factory, not the auto-configured RestClient.Builder bean, which
  // isn't present in this app) keeps this self-contained. A connect+read timeout keeps a slow-but-
  // not-erroring DataCite from blocking the serving thread indefinitely -- the spec promises
  // "timeout -> 502" (see the catch below).
  public DataciteClient(ObjectMapper objectMapper) {
    var requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(3));
    requestFactory.setReadTimeout(Duration.ofSeconds(10));
    this.http = RestClient.builder()
        .baseUrl("https://api.datacite.org")
        // Same etiquette as CrossrefClient: identify the client + a contact.
        .defaultHeader("User-Agent",
            "coldp-editor/0.1 (+https://github.com/CatalogueOfLife/coldp-editor; mailto:info@catalogueoflife.org)")
        .requestFactory(requestFactory)
        .build();
    this.objectMapper = objectMapper;
  }

  // Returns the `data.attributes` node of GET /dois/{doi}. 404 -> DOI not found; any other failure
  // -> 502.
  public JsonNode fetchDoi(String doi) {
    try {
      String body = http.get().uri("/dois/{doi}", doi).retrieve().body(String.class);
      if (body == null) {
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "empty DataCite response");
      }
      return objectMapper.readTree(body).path("data").path("attributes");
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "DOI not found");
      }
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DataCite request failed");
    } catch (RestClientException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DataCite unavailable");
    }
  }
}
