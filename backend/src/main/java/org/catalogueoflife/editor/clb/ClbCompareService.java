package org.catalogueoflife.editor.clb;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import life.catalogue.api.model.Name;
import life.catalogue.api.model.NameUsageBase;
import life.catalogue.api.model.SimpleName;
import life.catalogue.api.model.Synonym;
import life.catalogue.api.model.Synonymy;
import life.catalogue.api.model.UsageInfo;
import org.catalogueoflife.editor.clb.ClbImportClient.ClbDatasetRef;
import org.catalogueoflife.editor.clb.ClbImportClient.ClbGlobalUsageHit;
import org.catalogueoflife.editor.clb.dto.ClbComparison;
import org.catalogueoflife.editor.clb.dto.ClbRankName;
import org.catalogueoflife.editor.clb.dto.ClbSynonym;
import org.springframework.stereotype.Service;

// Builds the CLB side of a focal-taxon comparison from a fetched UsageInfo (name/authorship/rank/
// status + higher classification + flattened synonyms), and proxies the all-datasets name search.
@Service
public class ClbCompareService {

  private final ClbImportClient client;
  private final ClbDatasetLabelService datasets;

  public ClbCompareService(ClbImportClient client, ClbDatasetLabelService datasets) {
    this.client = client;
    this.datasets = datasets;
  }

  public ClbComparison compare(String datasetKey, String taxonId) {
    UsageInfo info = client.usageInfo(datasetKey, taxonId);
    return map(info, datasetKey, client.datasetTitle(datasetKey));
  }

  /**
   * CLB's global name search also lists usages of private datasets, which our anonymous client can
   * then not open (the comparison 401s). Drop those hits, and fill in each hit's dataset title from
   * the (cached) dataset lookup. A dataset CLB couldn't be asked about is kept, as before.
   */
  public List<ClbGlobalUsageHit> searchAllDatasets(String q, String rank) {
    List<ClbGlobalUsageHit> hits = client.searchUsagesAllDatasets(q, rank);
    Map<String, ClbDatasetRef> refs = datasets.datasets(hits.stream().map(ClbGlobalUsageHit::datasetKey).toList());
    List<ClbGlobalUsageHit> out = new ArrayList<>();
    for (ClbGlobalUsageHit h : hits) {
      ClbDatasetRef ref = refs.get(h.datasetKey());
      if (ref == null) {
        out.add(h);
      } else if (ref.accessible()) {
        out.add(new ClbGlobalUsageHit(h.datasetKey(), ref.title() != null ? ref.title() : h.datasetTitle(),
            h.id(), h.scientificName(), h.authorship(), h.rank(), h.status()));
      }
    }
    return out;
  }

  static ClbComparison map(UsageInfo info, String datasetKey, String datasetTitle) {
    NameUsageBase u = info.getUsage();
    Name n = u.getName();
    String link = "https://www.checklistbank.org/dataset/" + datasetKey + "/taxon/" + u.getId();

    List<ClbRankName> classification = new ArrayList<>();
    if (info.getClassification() != null) {
      for (SimpleName sn : info.getClassification()) {
        classification.add(new ClbRankName(
            lower(sn.getRank() == null ? null : sn.getRank().name()), sn.getName()));
      }
    }

    List<ClbSynonym> synonyms = new ArrayList<>();
    Synonymy syn = info.getSynonyms();
    if (syn != null) {
      addSyns(synonyms, syn.getHomotypic());
      addSyns(synonyms, syn.getHeterotypic());
      addSyns(synonyms, syn.getMisapplied());
    }

    return new ClbComparison(datasetKey, datasetTitle, u.getId(), link,
        n == null ? null : n.getScientificName(), n == null ? null : n.getAuthorship(),
        lower(n == null || n.getRank() == null ? null : n.getRank().name()),
        u.getStatus() == null ? null : u.getStatus().name(), classification, synonyms);
  }

  private static void addSyns(List<ClbSynonym> out, List<Synonym> syns) {
    if (syns == null) return;
    for (Synonym s : syns) {
      Name sn = s.getName();
      if (sn == null) continue;
      out.add(new ClbSynonym(sn.getScientificName(), sn.getAuthorship(),
          s.getStatus() == null ? null : s.getStatus().name()));
    }
  }

  private static String lower(String s) {
    return s == null ? null : s.toLowerCase(Locale.ROOT);
  }
}
