package org.catalogueoflife.editor.clb;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import life.catalogue.api.model.Name;
import life.catalogue.api.model.NameUsageBase;
import life.catalogue.api.model.SimpleName;
import life.catalogue.api.model.Synonym;
import life.catalogue.api.model.Synonymy;
import life.catalogue.api.model.UsageInfo;
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

  public ClbCompareService(ClbImportClient client) {
    this.client = client;
  }

  public ClbComparison compare(String datasetKey, String taxonId) {
    UsageInfo info = client.usageInfo(datasetKey, taxonId);
    return map(info, datasetKey, client.datasetTitle(datasetKey));
  }

  public List<ClbGlobalUsageHit> searchAllDatasets(String q, String rank) {
    return client.searchUsagesAllDatasets(q, rank);
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
