package org.catalogueoflife.editor.clb;

import java.util.List;
import java.util.Locale;
import life.catalogue.api.model.Name;
import life.catalogue.api.model.NameUsageBase;
import life.catalogue.api.model.UsageInfo;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.clb.ClbImportClient.ClbDatasetHit;
import org.catalogueoflife.editor.clb.ClbImportClient.ClbUsageHit;
import org.catalogueoflife.editor.clb.dto.ClbImportRequest;
import org.catalogueoflife.editor.clb.dto.ClbImportSummary;
import org.catalogueoflife.editor.clb.dto.ClbResolvedTaxon;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// The Direct CLB Taxon Import HTTP surface: a single synchronous POST (unlike the ColDP import's
// async start/poll -- a CLB subtree is capped at coldp.clb-import.max-usages and involves no file
// upload, so it's fast enough to serve inline). Owner/editor + focal-usage-accepted are both
// enforced in ClbImportService itself, not here.
//
// Plus (Task 3) three read-only GET proxies the import modal's source picker calls BEFORE that
// POST -- dataset search / taxon search / URL-paste resolution -- all going straight through
// ClbImportClient with no project scoping at all (picking a CLB source doesn't touch any project
// yet, so there is nothing to check an editor/owner role against here; every route is still behind
// Spring Security's default anyRequest().authenticated(), see SecurityConfig). Proxying (rather than
// having the browser call api.checklistbank.org directly) sidesteps CORS and keeps the configured
// CLB base URL server-side.
@RestController
public class ClbImportController {

  private final ClbImportService service;
  private final ClbImportClient client;
  private final CurrentUser currentUser;

  public ClbImportController(ClbImportService service, ClbImportClient client, CurrentUser currentUser) {
    this.service = service;
    this.client = client;
    this.currentUser = currentUser;
  }

  @PostMapping("/api/projects/{pid}/usages/{focalId}/clb-import")
  public ClbImportSummary importFromClb(@PathVariable int pid, @PathVariable int focalId,
      @RequestBody ClbImportRequest req) {
    return service.importFromClb(currentUser.require().getId(), pid, focalId, req);
  }

  @GetMapping("/api/clb/datasets")
  public List<ClbDatasetHit> searchDatasets(@RequestParam(defaultValue = "") String q) {
    currentUser.require();
    return client.searchDatasets(q);
  }

  @GetMapping("/api/clb/{datasetKey}/usages")
  public List<ClbUsageHit> searchUsages(@PathVariable String datasetKey,
      @RequestParam(defaultValue = "") String q, @RequestParam(required = false) String rank) {
    currentUser.require();
    return client.searchUsages(datasetKey, q, rank);
  }

  // Resolves a pasted URL's (datasetKey, taxonId) (see ClbTaxonUrl.parse, run client-side first for
  // instant feedback) to a light {@link ClbResolvedTaxon} just so the modal can show what it's about
  // to import -- 404 (from ClbImportClient.usageInfo, "no such taxon in that dataset") propagates
  // straight through unchanged.
  @GetMapping("/api/clb/{datasetKey}/resolve/{taxonId}")
  public ClbResolvedTaxon resolve(@PathVariable String datasetKey, @PathVariable String taxonId) {
    currentUser.require();
    UsageInfo info = client.usageInfo(datasetKey, taxonId);
    NameUsageBase usage = info.getUsage();
    Name name = usage.getName();
    String rank = name.getRank() == null ? null : name.getRank().name().toLowerCase(Locale.ROOT);
    return new ClbResolvedTaxon(datasetKey, taxonId, name.getScientificName(), rank);
  }
}
