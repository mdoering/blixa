package org.catalogueoflife.editor.clb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import life.catalogue.api.jackson.ApiModule;
import life.catalogue.api.model.Taxon;
import life.catalogue.api.model.UsageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

// Read-only client against ChecklistBank's public JSON API for Direct CLB Taxon Import (Task 1):
// resolving a pasted URL's (datasetKey, id) (see ClbTaxonUrl) to search hits / a full UsageInfo /
// a taxon's direct children, all of which ClbUsageMapper then turns into our own model. Mirrors
// ClbMatchClient's construction (self-contained RestClient.builder() -- no autoconfigured
// RestClient.Builder bean in this app -- plus a connect+read timeout so a slow-but-not-erroring CLB
// can't block the serving thread indefinitely) and its error mapping (any non-2xx -> a clear
// ResponseStatusException instead of leaking a raw RestClientException).
//
// Deserializes the CLB `api` model (life.catalogue.api.model.*, on the classpath transitively via
// the `reader` dependency -- see pom.xml) with a private copy of life.catalogue.api.jackson.
// ApiModule.MAPPER: the Jackson **2** (com.fasterxml.jackson) ObjectMapper the CLB codebase itself
// uses for exactly this model, already configured lenient (FAIL_ON_UNKNOWN_PROPERTIES disabled) and
// with all of the api model's custom (de)serializers (Country, CSLType, URI, permissive enum
// matching, ...) -- NOT the app's own Jackson **3** (tools.jackson) ObjectMapper, which knows
// nothing about this package. .copy() (rather than the shared static ApiModule.MAPPER instance
// directly) so USAGE_INFO_MIXIN below (see usageInfo()) is scoped to this client alone.
@Service
public class ClbImportClient {

  private static final int PAGE_LIMIT = 100;

  // UsageInfo.usage is a `private final NameUsageBase usage` with NO setter -- but Jackson still
  // discovers it as a deserializable property via direct (accessible) field reflection, and fails
  // outright since NameUsageBase is abstract with no @JsonTypeInfo/@JsonSubTypes anywhere in the CLB
  // api model (verified empirically: a plain `mapper.readValue(body, UsageInfo.class)` throws
  // InvalidDefinitionException -- CLB itself apparently never round-trips UsageInfo back through
  // Jackson, only ever serializes it out, so this path was never exercised upstream). This mixin
  // tells Jackson to skip "usage" entirely when populating an already-constructed UsageInfo (see
  // usageInfo() below, which deserializes `usage` separately as a concrete Taxon -- the only type
  // GET .../taxon/{id}/info can ever return -- and passes it to UsageInfo's one constructor first).
  @JsonIgnoreProperties({"usage"})
  private interface UsageInfoMixin {}

  private final RestClient http;
  private final ObjectMapper mapper;

  // @Autowired is required here (unlike ClbMatchClient's single constructor, which Spring picks
  // implicitly) because this class has a SECOND, package-private constructor below for tests --
  // with more than one constructor present, Spring needs exactly one marked @Autowired to disambiguate,
  // otherwise it falls back to looking for a plain no-arg constructor and fails to instantiate the
  // bean at all.
  @Autowired
  public ClbImportClient(@Value("${coldp.clb.base-url:https://api.checklistbank.org}") String baseUrl) {
    this(buildHttp(baseUrl));
  }

  // Package-private: lets ClbImportClientIT bind a Spring MockRestServiceServer to a
  // RestClient.Builder and construct the client directly around the resulting RestClient, exercising
  // the real GET + Jackson-2-deserialize code paths below against canned responses, with no live
  // network call and no extra mocking dependency (WireMock/MockWebServer) needed. Production code
  // never calls this overload -- only the (String baseUrl) constructor above, which Spring invokes.
  ClbImportClient(RestClient http) {
    this.http = http;
    this.mapper = ApiModule.MAPPER.copy();
    this.mapper.addMixIn(UsageInfo.class, UsageInfoMixin.class);
  }

  private static RestClient buildHttp(String baseUrl) {
    var requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(3));
    requestFactory.setReadTimeout(Duration.ofSeconds(10));
    return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
  }

  public record ClbDatasetHit(String key, String title, String alias) {}

  public record ClbUsageHit(String id, String scientificName, String rank, String status) {}

  // A name hit from the global (all-datasets) search, carrying the dataset it belongs to.
  public record ClbGlobalUsageHit(String datasetKey, String datasetTitle, String id,
      String scientificName, String authorship, String rank, String status) {}

  /** GET /dataset/{key}, returning its title (null if unavailable) — for showing what a pasted URL points at. */
  public String datasetTitle(String key) {
    JsonNode ds = getPage(UriComponentsBuilder.fromPath("/dataset/{ds}"), key);
    return text(ds, "title");
  }

  /**
   * GET /dataset/{key}, returning a human-readable label to show in place of the opaque key: the
   * dataset's short {@code alias} when set, else its {@code title} (null if neither / unavailable).
   * Reads only those two fields off the response, not the full dataset metadata.
   */
  public String datasetLabel(String key) {
    JsonNode ds = getPage(UriComponentsBuilder.fromPath("/dataset/{ds}"), key);
    String alias = text(ds, "alias");
    return alias != null && !alias.isBlank() ? alias : text(ds, "title");
  }

  /** GET /dataset?q={q}&limit=20, parsing the ResultPage's {@code .result[]} into hits. */
  public List<ClbDatasetHit> searchDatasets(String q) {
    JsonNode page = getPage(UriComponentsBuilder.fromPath("/dataset")
        .queryParam("q", q)
        .queryParam("limit", 20));
    List<ClbDatasetHit> out = new ArrayList<>();
    for (JsonNode n : page.path("result")) {
      out.add(new ClbDatasetHit(text(n, "key"), text(n, "title"), text(n, "alias")));
    }
    return out;
  }

  /**
   * GET /dataset/{datasetKey}/nameusage?q={q}[&rank={rank}]&limit=20. A hit's scientificName/rank
   * live under the nested {@code .name} object, not the top-level usage (verified against a live
   * CLB response), unlike {@code .status}, which is top-level.
   */
  public List<ClbUsageHit> searchUsages(String datasetKey, String q, String rank) {
    var uri = UriComponentsBuilder.fromPath("/dataset/{ds}/nameusage")
        .queryParam("q", q)
        .queryParam("limit", 20);
    if (rank != null && !rank.isBlank()) {
      uri.queryParam("rank", rank);
    }
    JsonNode page = getPage(uri, datasetKey);
    List<ClbUsageHit> out = new ArrayList<>();
    for (JsonNode n : page.path("result")) {
      JsonNode name = n.path("name");
      out.add(new ClbUsageHit(text(n, "id"), text(name, "scientificName"), text(name, "rank"), text(n, "status")));
    }
    return out;
  }

  // Global name search across ALL datasets (CLB /nameusage/search), each hit carrying its datasetKey.
  // Defensive parsing: the ES search may nest the usage under "usage" or flatten it, and the dataset
  // key may sit on the hit or the usage. datasetTitle is left null here (the UI shows the key) to
  // avoid a per-hit dataset lookup; NOTE: the exact response shape needs a live-CLB check.
  public List<ClbGlobalUsageHit> searchUsagesAllDatasets(String q, String rank) {
    var uri = UriComponentsBuilder.fromPath("/nameusage/search")
        .queryParam("q", q)
        .queryParam("content", "SCIENTIFIC_NAME")
        .queryParam("limit", 20);
    if (rank != null && !rank.isBlank()) {
      uri.queryParam("rank", rank);
    }
    JsonNode page = getPage(uri);
    List<ClbGlobalUsageHit> out = new ArrayList<>();
    for (JsonNode hit : page.path("result")) {
      JsonNode usage = hit.has("usage") ? hit.path("usage") : hit;
      JsonNode name = usage.path("name");
      String dk = text(hit, "datasetKey");
      if (dk == null || dk.isBlank()) dk = text(usage, "datasetKey");
      out.add(new ClbGlobalUsageHit(dk, null, text(usage, "id"), text(name, "scientificName"),
          text(name, "authorship"), text(name, "rank"), text(usage, "status")));
    }
    return out;
  }

  /**
   * GET /dataset/{datasetKey}/taxon/{id}/info, deserialized into the CLB api model's own
   * {@link UsageInfo} (usage/name/synonyms/distributions/vernacularNames/media/properties/estimates/
   * typeMaterial/nameRelations/references/publishedIn -- see ClbUsageMapper for the field-by-field
   * mapping into our own model). 404 (no such taxon in that dataset) is distinguished from every
   * other failure so the caller can show "not found" rather than a generic upstream-error message.
   * <p>
   * {@code usage} is deserialized separately, straight into {@link Taxon} (the endpoint's name says
   * it all -- {@code .../taxon/{id}/...} can only ever return a taxon, never a synonym), then handed
   * to {@code UsageInfo}'s one constructor; the rest of the payload is layered on top via {@code
   * readerForUpdating} against that already-built instance (see {@link UsageInfoMixin}'s javadoc for
   * why a single {@code readValue(body, UsageInfo.class)} call cannot work here).
   */
  public UsageInfo usageInfo(String datasetKey, String id) {
    String uri = UriComponentsBuilder.fromPath("/dataset/{ds}/taxon/{id}/info").encode()
        .buildAndExpand(datasetKey, id).toUriString();
    String body;
    try {
      body = http.get().uri(uri).retrieve().body(String.class);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CLB taxon not found");
      }
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "CLB taxon lookup failed");
    } catch (RestClientException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "CLB unavailable");
    }
    try {
      JsonNode root = mapper.readTree(body);
      Taxon usage = mapper.treeToValue(root.path("usage"), Taxon.class);
      UsageInfo info = new UsageInfo(usage);
      mapper.readerForUpdating(info).readValue(root);
      return info;
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "could not parse CLB taxon info");
    }
  }

  /**
   * Direct children of a taxon in the classification tree, paged through to completion.
   * <p>
   * Endpoint choice: {@code GET /dataset/{key}/tree/{id}/children} (a {@code ResultPage<TreeNode>})
   * rather than {@code GET /dataset/{key}/nameusage?parentID={id}} -- verified live against
   * api.checklistbank.org that the latter's {@code parentID} param is silently ignored by the
   * nameusage search (it returned the dataset's entire ~8M-row unscoped result set, not the ~18
   * actual children of the test genus), so it cannot be used at all. {@code tree/{id}/children}
   * returns exactly the taxon's classification children -- i.e. every usage whose {@code parentId}
   * is this taxon (both ACCEPTED and PROVISIONALLY_ACCEPTED; a synonym never has tree children, so
   * this is inherently synonym-free) -- confirmed against a live genus with a mix of both statuses
   * among its children.
   */
  public List<String> childrenIds(String datasetKey, String id) {
    List<String> ids = new ArrayList<>();
    int offset = 0;
    while (true) {
      JsonNode page = getPage(UriComponentsBuilder.fromPath("/dataset/{ds}/tree/{id}/children")
          .queryParam("offset", offset)
          .queryParam("limit", PAGE_LIMIT), datasetKey, id);
      JsonNode result = page.path("result");
      if (!result.isArray() || result.isEmpty()) {
        break;
      }
      for (JsonNode n : result) {
        String cid = text(n, "id");
        if (cid != null) {
          ids.add(cid);
        }
      }
      if (page.path("last").asBoolean(true) || result.size() < PAGE_LIMIT) {
        break;
      }
      offset += PAGE_LIMIT;
    }
    return ids;
  }

  // Shared GET-and-parse-as-JsonNode for the three ResultPage-shaped endpoints (searchDatasets/
  // searchUsages/childrenIds). usageInfo does its own request+parse above instead of going through
  // here since its 404 needs a distinct, more specific message ("CLB taxon not found") than the
  // generic mapping below.
  private JsonNode getPage(UriComponentsBuilder uri, Object... pathVars) {
    String u = uri.encode().buildAndExpand(pathVars).toUriString();
    try {
      String body = http.get().uri(u).retrieve().body(String.class);
      return mapper.readTree(body);
    } catch (RestClientResponseException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "CLB request failed");
    } catch (RestClientException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "CLB unavailable");
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "could not parse CLB response");
    }
  }

  private static String text(JsonNode n, String field) {
    JsonNode v = n.path(field);
    return v.isMissingNode() || v.isNull() ? null : v.asText();
  }
}
