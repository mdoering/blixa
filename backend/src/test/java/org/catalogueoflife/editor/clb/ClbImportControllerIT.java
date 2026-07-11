package org.catalogueoflife.editor.clb;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import life.catalogue.api.model.Name;
import life.catalogue.api.model.Taxon;
import life.catalogue.api.model.UsageInfo;
import life.catalogue.api.vocab.TaxonomicStatus;
import org.catalogueoflife.editor.clb.ClbImportClient.ClbDatasetHit;
import org.catalogueoflife.editor.clb.ClbImportClient.ClbUsageHit;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUserService;
import org.gbif.nameparser.api.Rank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

// GET /api/clb/datasets, /api/clb/{datasetKey}/usages and /api/clb/{datasetKey}/resolve/{taxonId}
// (Task 3's suggest proxy, exercised at the HTTP layer -- ClbImportController is otherwise a thin
// pass-through). ClbImportClient is entirely mocked, never a real network call to CLB -- mirrors
// how ColMatchIT mocks ClbMatchClient for the sibling col-match proxy.
@AutoConfigureMockMvc
@WithMockUser(username = "clbSuggestUser")
class ClbImportControllerIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;

  @MockitoBean ClbImportClient clb;

  private void ensureUser(String u) {
    if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u);
  }

  @Test
  void searchDatasetsProxies() throws Exception {
    ensureUser("clbSuggestUser");
    when(clb.searchDatasets(eq("cat")))
        .thenReturn(List.of(new ClbDatasetHit("3LXR", "Catalogue of Life", "COL")));

    mvc.perform(get("/api/clb/datasets").param("q", "cat"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].key").value("3LXR"))
        .andExpect(jsonPath("$[0].title").value("Catalogue of Life"))
        .andExpect(jsonPath("$[0].alias").value("COL"))
        .andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  void searchUsagesProxies() throws Exception {
    ensureUser("clbSuggestUser");
    when(clb.searchUsages(eq("3LXR"), eq("leo"), eq("species")))
        .thenReturn(List.of(new ClbUsageHit("6W3C4", "Panthera leo", "species", "accepted")));

    mvc.perform(get("/api/clb/3LXR/usages").param("q", "leo").param("rank", "species"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("6W3C4"))
        .andExpect(jsonPath("$[0].scientificName").value("Panthera leo"))
        .andExpect(jsonPath("$[0].rank").value("species"))
        .andExpect(jsonPath("$[0].status").value("accepted"))
        .andExpect(jsonPath("$.length()").value(1));

    verify(clb).searchUsages(eq("3LXR"), eq("leo"), eq("species"));
  }

  @Test
  void resolveReturnsLightShape() throws Exception {
    ensureUser("clbSuggestUser");
    Name n = new Name();
    n.setId("6W3C4-N");
    n.setScientificName("Panthera leo");
    n.setRank(Rank.SPECIES);
    Taxon t = new Taxon(n);
    t.setId("6W3C4");
    t.setStatus(TaxonomicStatus.ACCEPTED);
    when(clb.usageInfo(eq("3LXR"), eq("6W3C4"))).thenReturn(new UsageInfo(t));

    mvc.perform(get("/api/clb/3LXR/resolve/6W3C4"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.datasetKey").value("3LXR"))
        .andExpect(jsonPath("$.taxonId").value("6W3C4"))
        .andExpect(jsonPath("$.scientificName").value("Panthera leo"))
        .andExpect(jsonPath("$.rank").value("species"));
  }

  @Test
  void resolveNotFoundPropagates() throws Exception {
    ensureUser("clbSuggestUser");
    when(clb.usageInfo(eq("3LXR"), eq("bogus")))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "CLB taxon not found"));

    mvc.perform(get("/api/clb/3LXR/resolve/bogus"))
        .andExpect(status().isNotFound());
  }
}
