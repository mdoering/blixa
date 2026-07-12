package org.catalogueoflife.editor.coldp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

// Unit test for IdScopeService.filter: the live-vocab entries are mapped to IdScope(scope, title,
// link), with the five generic scopes (local/doi/url/urn/lsid) excluded, de-duplicated and sorted.
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
          {"scope":"ipni","title":"IPNI","link":"https://www.ipni.org"},
          {"scope":"col","title":"Catalogue of Life","link":"https://www.catalogueoflife.org/building/identifier"},
          {"scope":"inat"},
          {"scope":"col","title":"duplicate, dropped"},
          {"title":"missing-scope"},
          {"scope":""}
        ]
        """);

    List<IdScope> scopes = IdScopeService.filter(arr);

    // the five generic scopes are gone; the rest are de-duped (first occurrence wins) + sorted by
    // scope; blank/missing scope dropped; scope/title/link are all carried through.
    assertThat(scopes).containsExactly(
        new IdScope("col", "Catalogue of Life", "https://www.catalogueoflife.org/building/identifier"),
        new IdScope("inat", null, null),
        new IdScope("ipni", "IPNI", "https://www.ipni.org"));
    assertThat(scopes).extracting(IdScope::scope).doesNotContain("local", "doi", "url", "urn", "lsid");
  }

  @Test
  void nonArrayOrNullYieldsEmpty() {
    assertThat(IdScopeService.filter(null)).isEmpty();
    assertThat(IdScopeService.filter(MAPPER.readTree("{\"scope\":\"col\"}"))).isEmpty();
  }
}
