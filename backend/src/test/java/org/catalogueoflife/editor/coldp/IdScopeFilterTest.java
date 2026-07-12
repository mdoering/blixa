package org.catalogueoflife.editor.coldp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

// Unit test for IdScopeService.filter: the live-vocab entries are reduced to their `scope` values
// with the five generic scopes (local/doi/url/urn/lsid) excluded, de-duplicated and sorted.
class IdScopeFilterTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void excludesGenericScopesAndSorts() {
    var arr = MAPPER.readTree("""
        [
          {"scope":"local","title":"Local"},
          {"scope":"doi","title":"DOI"},
          {"scope":"url"},
          {"scope":"urn"},
          {"scope":"lsid"},
          {"scope":"ipni","title":"IPNI"},
          {"scope":"col"},
          {"scope":"inat"},
          {"scope":"col"},
          {"title":"missing-scope"},
          {"scope":""}
        ]
        """);

    List<String> scopes = IdScopeService.filter(arr);

    // the five generic scopes are gone; the rest are de-duped + sorted; blank/missing dropped
    assertThat(scopes).containsExactly("col", "inat", "ipni");
    assertThat(scopes).doesNotContain("local", "doi", "url", "urn", "lsid");
  }

  @Test
  void nonArrayOrNullYieldsEmpty() {
    assertThat(IdScopeService.filter(null)).isEmpty();
    assertThat(IdScopeService.filter(MAPPER.readTree("{\"scope\":\"col\"}"))).isEmpty();
  }
}
