package org.catalogueoflife.editor.bhl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Server-side Biodiversity Heritage Library (BHL) API v3 lookups. Isolated behind this component so
 * the external HTTP call can be @MockitoBean'd in tests (it makes live calls, so it's never
 * test-exercised, same as the AI adapters). Fixed host -> no SSRF surface; a read timeout keeps a
 * slow BHL from blocking the serving thread. Field names follow BHL v3's PascalCase JSON and are
 * validated on the first live call; mapping is defensive because BHL metadata is uneven.
 */
@Component
public class BhlClient {

  private static final String BASE = "https://www.biodiversitylibrary.org";

  private final BhlProperties props;
  private final ObjectMapper mapper;
  private final RestClient http;

  public BhlClient(BhlProperties props, ObjectMapper mapper) {
    this.props = props;
    this.mapper = mapper;
    var requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(5));
    requestFactory.setReadTimeout(Duration.ofSeconds(20));
    this.http = RestClient.builder().baseUrl(BASE).requestFactory(requestFactory).build();
  }

  // PublicationSearch (catalog metadata) -> candidate items to link a reference to.
  public List<BhlItem> publicationSearch(String term) {
    JsonNode root = get(
        "/api3?op=PublicationSearch&searchterm={t}&searchtype=C&apikey={k}&format=json",
        term, props.getApiKey());
    List<BhlItem> items = new ArrayList<>();
    for (JsonNode r : root.path("Result")) {
      Integer itemId = intOrNull(r.path("ItemID"));
      String title = str(r.path("Title"));
      String year = firstNonBlank(str(r.path("Date")), str(r.path("PublicationDate")));
      String url = itemId != null ? BASE + "/item/" + itemId : str(r.path("ItemUrl"));
      items.add(new BhlItem(itemId, title, authors(r.path("Authors")), year, url));
    }
    return items;
  }

  private JsonNode get(String uri, Object... vars) {
    try {
      String body = http.get().uri(uri, vars).retrieve().body(String.class);
      if (body == null) {
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "empty BHL response");
      }
      JsonNode root = mapper.readTree(body);
      if (!"ok".equalsIgnoreCase(str(root.path("Status")))) {
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
            "BHL error: " + str(root.path("ErrorMessage")));
      }
      return root;
    } catch (RestClientException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "BHL unavailable");
    }
  }

  private static String authors(JsonNode arr) {
    List<String> names = new ArrayList<>();
    for (JsonNode a : arr) {
      String n = str(a.path("Name"));
      if (!n.isBlank()) {
        names.add(n);
      }
    }
    return String.join("; ", names);
  }

  private static String str(JsonNode n) {
    return n == null || n.isMissingNode() || n.isNull() ? "" : n.asString();
  }

  private static Integer intOrNull(JsonNode n) {
    return n == null || n.isMissingNode() || n.isNull() ? null : n.asInt();
  }

  private static String firstNonBlank(String a, String b) {
    return a != null && !a.isBlank() ? a : b;
  }
}
