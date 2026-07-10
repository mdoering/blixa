package org.catalogueoflife.editor.name;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.editor.name.dto.RankName;
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

// Server-side name matching against a published ChecklistBank dataset via /match/nameusage. The
// dataset to match against is passed explicitly to match() -- ColMatchService (single-taxon "Match
// to COL" modal) always uses the configured COL default (coldp.col.match-dataset, see
// defaultColDataset()); the upcoming bulk job (Task 3+) will pass each configured scope's own
// dataset key instead. Isolated behind this component -- mirrors CrossrefClient -- so the external
// HTTP call can be @MockitoBean'd in tests (see ColMatchIT) and the response-shape mapping
// (ColMatchService.addCandidate) exercised apart from the network.
@Component
public class ClbMatchClient {

  // Our lowercase Rank (see RankName.rank / NameUsage.rank) -> CLB /match/nameusage higher-
  // classification query param name (life.catalogue.api.model.Classification). Ranks absent here
  // -- notably "species" -- are skipped when building the request: the spec matches on the full
  // higher classification only, never on species itself. Both botanical and zoological "section"
  // ranks map onto the single generic "section" param CLB exposes.
  private static final Map<String, String> CLASSIFICATION_PARAM = Map.ofEntries(
      Map.entry("superkingdom", "superkingdom"),
      Map.entry("kingdom", "kingdom"),
      Map.entry("subkingdom", "subkingdom"),
      Map.entry("superphylum", "superphylum"),
      Map.entry("phylum", "phylum"),
      Map.entry("subphylum", "subphylum"),
      Map.entry("superclass", "superclass"),
      Map.entry("class", "class"),
      Map.entry("subclass", "subclass"),
      Map.entry("superorder", "superorder"),
      Map.entry("order", "order"),
      Map.entry("suborder", "suborder"),
      Map.entry("superfamily", "superfamily"),
      Map.entry("family", "family"),
      Map.entry("subfamily", "subfamily"),
      Map.entry("tribe", "tribe"),
      Map.entry("subtribe", "subtribe"),
      Map.entry("genus", "genus"),
      Map.entry("subgenus", "subgenus"),
      Map.entry("section_botany", "section"),
      Map.entry("section_zoology", "section"));

  private final RestClient http;
  private final ObjectMapper objectMapper;
  private final String matchDataset;

  // RestClient.builder() (the static factory, not the auto-configured RestClient.Builder bean,
  // which isn't present in this app) keeps this self-contained -- same pattern as CrossrefClient.
  // A connect+read timeout keeps a slow-but-not-erroring CLB from blocking the serving thread
  // indefinitely -- the spec promises "timeout -> 502" (see the catch below).
  public ClbMatchClient(ObjectMapper objectMapper,
      @Value("${coldp.clb.base-url:https://api.checklistbank.org}") String baseUrl,
      @Value("${coldp.col.match-dataset:3LXR}") String matchDataset) {
    var requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(3));
    requestFactory.setReadTimeout(Duration.ofSeconds(10));
    this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    this.objectMapper = objectMapper;
    this.matchDataset = matchDataset;
  }

  // The configured default COL dataset key (coldp.col.match-dataset, normally 3LXR) that
  // ColMatchService's single-taxon match always targets. Exposed so other callers that don't yet
  // have a per-scope dataset key of their own (e.g. ColMatchJobService.matchOne, until Task 3 wires
  // up per-scope keys) can fall back to the same default rather than duplicating the config lookup.
  public String defaultColDataset() {
    return matchDataset;
  }

  // GET /dataset/{datasetKey}/match/nameusage?scientificName=...&verbose=true[&authorship=...
  // &rank=...&code=...&<higher classification params>]. verbose=true is always set so alternatives
  // (e.g. homonyms) come back even for a confident (EXACT) match -- ColMatchService needs them for
  // disambiguation. Returns the raw response root (usage/type/alternatives); failures map to 502,
  // exactly like CrossrefClient.fetchWork.
  public JsonNode match(String datasetKey, String sciName, String authorship, String rank,
      String code, List<RankName> classification) {
    var uri = UriComponentsBuilder.fromPath("/dataset/{ds}/match/nameusage")
        .queryParam("scientificName", sciName)
        .queryParam("verbose", true);
    if (authorship != null) {
      uri.queryParam("authorship", authorship);
    }
    if (rank != null) {
      uri.queryParam("rank", rank);
    }
    if (code != null) {
      uri.queryParam("code", code);
    }
    for (RankName rn : classification) {
      String param = CLASSIFICATION_PARAM.get(rn.rank());
      if (param != null && rn.name() != null) {
        uri.queryParam(param, rn.name());
      }
    }
    try {
      // .encode() before expanding: scientific names/authorships routinely contain spaces and other
      // characters that are not legal, unescaped, in a URI -- left unencoded, RestClient's
      // uri(String) would reject the resulting string outright.
      String body = http.get()
          .uri(uri.encode().buildAndExpand(datasetKey).toUriString())
          .retrieve()
          .body(String.class);
      return objectMapper.readTree(body);
    } catch (RestClientResponseException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "COL matching failed");
    } catch (RestClientException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "COL matching unavailable");
    }
  }
}
