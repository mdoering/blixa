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

  // All pages of an item (browse / jump to a known page number).
  public List<BhlPage> itemPages(int itemId) {
    JsonNode root = get("/api3?op=GetItemMetadata&id={id}&pages=t&ocr=f&parts=f&apikey={k}&format=json",
        itemId, props.getApiKey());
    List<BhlPage> pages = new ArrayList<>();
    for (JsonNode p : root.path("Result").path(0).path("Pages")) {
      pages.add(page(p));
    }
    return pages;
  }

  // Pages of the given item where `name` appears, from BHL's OCR name index (the likely protologue).
  // GetNameMetadata returns occurrences across all of BHL; we keep only those in `itemId`. Best-effort
  // -- BHL's name-index nesting is uneven, so handle both item-with-Pages and flat-page result shapes.
  public List<BhlPage> namePagesInItem(String name, int itemId) {
    JsonNode root = get("/api3?op=GetNameMetadata&name={n}&apikey={k}&format=json",
        name, props.getApiKey());
    List<BhlPage> pages = new ArrayList<>();
    for (JsonNode r : root.path("Result")) {
      Integer resultItemId = intOrNull(r.path("ItemID"));
      if (resultItemId == null || resultItemId != itemId) {
        continue;
      }
      JsonNode nested = r.path("Pages");
      if (nested.isArray() && !nested.isEmpty()) {
        for (JsonNode p : nested) {
          pages.add(page(p));
        }
      } else if (!r.path("PageID").isMissingNode()) {
        pages.add(page(r)); // flat page entry
      }
    }
    return pages;
  }

  private static BhlPage page(JsonNode p) {
    Integer pageId = intOrNull(p.path("PageID"));
    String number = str(p.path("PageNumbers").path(0).path("Number"));
    String url = pageId != null ? BASE + "/page/" + pageId : null;
    String thumb = pageId != null ? BASE + "/pagethumb/" + pageId : null;
    return new BhlPage(pageId, number.isBlank() ? null : number, url, thumb);
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
