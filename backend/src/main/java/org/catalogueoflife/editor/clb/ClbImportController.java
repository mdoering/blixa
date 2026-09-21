package org.catalogueoflife.editor.clb;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import life.catalogue.api.model.Name;
import life.catalogue.api.model.NameUsageBase;
import life.catalogue.api.model.UsageInfo;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.clb.ClbImportClient.ClbDatasetHit;
import org.catalogueoflife.editor.clb.ClbImportClient.ClbGlobalUsageHit;
import org.catalogueoflife.editor.clb.ClbImportClient.ClbUsageHit;
import org.catalogueoflife.editor.clb.dto.ClbClassificationPreview;
import org.catalogueoflife.editor.clb.dto.ClbClassificationRequest;
import org.catalogueoflife.editor.clb.dto.ClbClassificationResult;
import org.catalogueoflife.editor.clb.dto.ClbComparison;
import org.catalogueoflife.editor.clb.dto.ClbCopyRequest;
import org.catalogueoflife.editor.clb.dto.ClbCopyResult;
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
  private final ClbCompareService compareService;
  private final ClbDatasetLabelService datasetLabels;
  private final ClbClassificationService classification;
  private final ClbVocabService vocab;
  private final CurrentUser currentUser;

  public ClbImportController(ClbImportService service, ClbImportClient client,
      ClbCompareService compareService, ClbDatasetLabelService datasetLabels,
      ClbClassificationService classification, ClbVocabService vocab, CurrentUser currentUser) {
    this.service = service;
    this.vocab = vocab;
    this.classification = classification;
    this.client = client;
    this.compareService = compareService;
    this.datasetLabels = datasetLabels;
    this.currentUser = currentUser;
  }

  // Resolve one or more CLB dataset keys to their human-readable label (alias, else title) for
  // display in place of the opaque key. Cached server-side (ClbDatasetLabelService); an unresolvable
  // key maps to itself. Returns {key: label} for every non-blank key requested.
  @GetMapping("/api/clb/dataset-labels")
  public java.util.Map<String, String> datasetLabels(@RequestParam(name = "key") List<String> keys) {
    currentUser.require();
    return datasetLabels.labels(keys);
  }

  @PostMapping("/api/projects/{pid}/usages/{focalId}/clb-import")
  public ClbImportSummary importFromClb(@PathVariable int pid, @PathVariable int focalId,
      @RequestBody ClbImportRequest req) {
    return service.importFromClb(currentUser.require().getId(), pid, focalId, req);
  }

  // Compare-with-CLB "«": copy chosen synonym / vernacular records of a CLB taxon onto the focal usage.
  @PostMapping("/api/projects/{pid}/usages/{focalId}/clb-copy")
  public ClbCopyResult copyFromClb(@PathVariable int pid, @PathVariable int focalId,
      @RequestBody ClbCopyRequest req) {
    return service.copyFromClb(currentUser.require().getId(), pid, focalId, req);
  }

  // Compare-with-CLB "wire into tree": preview how the CLB higher classification maps onto our tree...
  @GetMapping("/api/projects/{pid}/usages/{focalId}/clb-classification")
  public ClbClassificationPreview classificationPreview(@PathVariable int pid, @PathVariable int focalId,
      @RequestParam String datasetKey, @RequestParam String taxonId) {
    return classification.preview(currentUser.require().getId(), pid, focalId, datasetKey, taxonId);
  }

  // ...and apply it: create the chosen missing ancestors and re-parent the focal usage.
  @PostMapping("/api/projects/{pid}/usages/{focalId}/clb-classification")
  public ClbClassificationResult classificationApply(@PathVariable int pid, @PathVariable int focalId,
      @RequestBody ClbClassificationRequest req) {
    return classification.apply(currentUser.require().getId(), pid, focalId, req);
  }

  // ISO 639-3 code -> English language name (CLB's /vocab/language), cached server-side; the SPA loads
  // it once per session to render vernacular languages by name.
  @GetMapping("/api/clb/vocab/languages")
  public org.springframework.http.ResponseEntity<Map<String, String>> languages() {
    currentUser.require();
    return org.springframework.http.ResponseEntity.ok()
        .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofDays(1)).cachePrivate())
        .body(vocab.languages());
  }

  // ISO 3166 code (2- and 3-letter, upper case) -> English country name (CLB's /vocab/country), same
  // caching as languages.
  @GetMapping("/api/clb/vocab/countries")
  public org.springframework.http.ResponseEntity<Map<String, String>> countries() {
    currentUser.require();
    return org.springframework.http.ResponseEntity.ok()
        .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofDays(1)).cachePrivate())
        .body(vocab.countries());
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

  // Global name search across ALL datasets (each hit carries its dataset) -- the "search everywhere"
  // mode of the compare picker. Single-segment /usages, distinct from /{datasetKey}/usages above.
  @GetMapping("/api/clb/usages")
  public List<ClbGlobalUsageHit> searchAllDatasets(@RequestParam(defaultValue = "") String q,
      @RequestParam(required = false) String rank) {
    currentUser.require();
    return compareService.searchAllDatasets(q, rank);
  }

  // The CLB side of a focal-taxon comparison: name/authorship/rank/status + classification + synonyms.
  @GetMapping("/api/clb/{datasetKey}/compare/{taxonId}")
  public ClbComparison compare(@PathVariable String datasetKey, @PathVariable String taxonId) {
    currentUser.require();
    return compareService.compare(datasetKey, taxonId);
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
    // Best-effort: a title-lookup failure must not break resolving the taxon (the important part).
    String datasetTitle;
    try {
      datasetTitle = client.datasetTitle(datasetKey);
    } catch (RuntimeException e) {
      datasetTitle = null;
    }
    return new ClbResolvedTaxon(datasetKey, taxonId, name.getScientificName(), rank, datasetTitle);
  }
}
