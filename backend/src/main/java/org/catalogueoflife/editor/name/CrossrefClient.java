package org.catalogueoflife.editor.name;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Server-side Crossref lookups for DOI resolution. Isolated behind this component so the external
// HTTP call can be @MockitoBean'd in tests and the mapping (RefMapping.fromCrossref) tested apart.
@Component
public class CrossrefClient {

  private final RestClient http;
  private final ObjectMapper objectMapper;

  // RestClient.builder() (the static factory, not the auto-configured RestClient.Builder bean, which
  // isn't present in this app) keeps this self-contained.
  public CrossrefClient(ObjectMapper objectMapper) {
    this.http = RestClient.builder()
        .baseUrl("https://api.crossref.org")
        // Crossref etiquette: identify the client + a contact so we land in the "polite" pool.
        .defaultHeader("User-Agent",
            "coldp-editor/0.1 (+https://github.com/CatalogueOfLife/coldp-editor; mailto:info@catalogueoflife.org)")
        .build();
    this.objectMapper = objectMapper;
  }

  // Returns the `message` node of GET /works/{doi}. 404 -> DOI not found; any other failure -> 502.
  public JsonNode fetchWork(String doi) {
    try {
      String body = http.get().uri("/works/{doi}", doi).retrieve().body(String.class);
      if (body == null) {
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "empty Crossref response");
      }
      return objectMapper.readTree(body).path("message");
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "DOI not found");
      }
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Crossref request failed");
    } catch (RestClientException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Crossref unavailable");
    }
  }
}
