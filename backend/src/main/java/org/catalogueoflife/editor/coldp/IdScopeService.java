package org.catalogueoflife.editor.coldp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
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
  private volatile List<String> cached;

  public IdScopeService(@Value("${coldp.clb.base-url:https://api.checklistbank.org}") String baseUrl,
      ObjectMapper objectMapper) {
    var requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(3));
    requestFactory.setReadTimeout(Duration.ofSeconds(15));
    this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    this.objectMapper = objectMapper;
  }

  public List<String> scopes() {
    List<String> c = cached;
    if (c != null) {
      return c;
    }
    try {
      String body = http.get().uri("/vocab/identifier-scope").retrieve().body(String.class);
      List<String> scopes = filter(objectMapper.readTree(body));
      cached = scopes;
      return scopes;
    } catch (Exception e) {
      return List.of(); // don't cache a failure; the next request retries the live vocab
    }
  }

  // Extract each vocab entry's `scope`, drop the excluded generic scopes, de-duplicate and sort.
  static List<String> filter(JsonNode arr) {
    if (arr == null || !arr.isArray()) {
      return List.of();
    }
    Set<String> out = new TreeSet<>();
    for (JsonNode n : arr) {
      JsonNode s = n.path("scope");
      if (s.isMissingNode() || s.isNull()) {
        continue;
      }
      String scope = s.asString();
      if (scope != null && !scope.isBlank() && !EXCLUDED.contains(scope.toLowerCase())) {
        out.add(scope.trim());
      }
    }
    return new ArrayList<>(out);
  }
}
