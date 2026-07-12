package org.catalogueoflife.editor.coldp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Loads the ColDP identifier-scope vocabulary from the live ChecklistBank vocab endpoint
// (GET {clb-base-url}/vocab/identifier-scope -> [{scope,title,link},...], 400+ registries) rather
// than the small in-JVM life.catalogue.api.model.Identifier.Scope enum, which only lists ~15 scopes.
// The five generic / non-registry scopes (local, doi, url, urn, lsid) are excluded -- only real
// source-registry scopes are offered as a project's identifier scopes. The vocab is stable, so it's
// fetched once and cached for the app's lifetime; a failed fetch returns an empty list and is NOT
// cached, so the next request retries (the scope field is an Autocomplete suggestion list, not a
// hard value restriction, so an empty list degrades gracefully).
@Service
public class IdScopeService {

  private static final Set<String> EXCLUDED = Set.of("local", "doi", "url", "urn", "lsid");

  private final RestClient http;
  private final ObjectMapper objectMapper;
  private volatile List<IdScope> cached;

  public IdScopeService(@Value("${coldp.clb.base-url:https://api.checklistbank.org}") String baseUrl,
      ObjectMapper objectMapper) {
    var requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(3));
    requestFactory.setReadTimeout(Duration.ofSeconds(15));
    this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    this.objectMapper = objectMapper;
  }

  public List<IdScope> scopes() {
    List<IdScope> c = cached;
    if (c != null) {
      return c;
    }
    try {
      String body = http.get().uri("/vocab/identifier-scope").retrieve().body(String.class);
      List<IdScope> scopes = filter(objectMapper.readTree(body));
      cached = scopes;
      return scopes;
    } catch (Exception e) {
      return List.of(); // don't cache a failure; the next request retries the live vocab
    }
  }

  // Extract each vocab entry's scope/title/link, drop the excluded generic scopes, de-duplicate by
  // scope (first occurrence wins) and sort by scope.
  static List<IdScope> filter(JsonNode arr) {
    if (arr == null || !arr.isArray()) {
      return List.of();
    }
    Map<String, IdScope> out = new TreeMap<>();
    for (JsonNode n : arr) {
      JsonNode s = n.path("scope");
      if (s.isMissingNode() || s.isNull()) {
        continue;
      }
      String scope = s.asString();
      if (scope != null && !scope.isBlank() && !EXCLUDED.contains(scope.toLowerCase())) {
        scope = scope.trim();
        out.putIfAbsent(scope, new IdScope(scope, text(n.path("title")), text(n.path("link"))));
      }
    }
    return new ArrayList<>(out.values());
  }

  private static String text(JsonNode n) {
    return n == null || n.isMissingNode() || n.isNull() ? null : n.asString();
  }
}
