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
import life.catalogue.api.model.NameUsageRelation;
import life.catalogue.api.model.Reference;
import life.catalogue.api.model.TypeMaterial;
import life.catalogue.api.model.UsageInfo;
import life.catalogue.api.model.VernacularName;
import org.catalogueoflife.editor.clb.ClbImportClient.ClbDatasetRef;
import org.catalogueoflife.editor.clb.ClbImportClient.ClbGlobalUsageHit;
import org.catalogueoflife.editor.clb.dto.ClbComparison;
import org.catalogueoflife.editor.clb.dto.ClbNameRelation;
import org.catalogueoflife.editor.clb.dto.ClbRankName;
import org.catalogueoflife.editor.clb.dto.ClbTypeMaterial;
import org.catalogueoflife.editor.clb.dto.ClbSynonym;
import org.catalogueoflife.editor.clb.dto.ClbVernacular;
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
    // CLB's UI serves a synonym under /nameusage/ (its /taxon/ route is for accepted taxa only).
    String acceptedName = null;
    if (u instanceof Synonym s && s.getAccepted() != null && s.getAccepted().getName() != null) {
      acceptedName = label(s.getAccepted().getName());
    }
    String link = "https://www.checklistbank.org/dataset/" + datasetKey
        + (u instanceof Synonym ? "/nameusage/" : "/taxon/") + u.getId();

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

    List<ClbVernacular> vernaculars = new ArrayList<>();
    if (info.getVernacularNames() != null) {
      for (VernacularName vn : info.getVernacularNames()) {
        vernaculars.add(new ClbVernacular(vn.getId() == null ? null : String.valueOf(vn.getId()),
            vn.getName(), vn.getLanguage(),
            vn.getCountry() == null ? null : vn.getCountry().getIso2LetterCode()));
      }
    }

    String nameId = n == null ? null : n.getId();
    List<ClbTypeMaterial> types = new ArrayList<>();
    List<TypeMaterial> tms = nameId == null || info.getTypeMaterial() == null
        ? null : info.getTypeMaterial().get(nameId);
    if (tms != null) {
      for (TypeMaterial tm : tms) {
        types.add(new ClbTypeMaterial(tm.getId(), lower(tm.getStatus() == null ? null : tm.getStatus().name()),
            tm.getCitation(), tm.getCatalogNumber(), tm.getInstitutionCode(), tm.getLocality()));
      }
    }

    // Only the focal name's own relations; the related name is looked up in the info's name map.
    List<ClbNameRelation> relations = new ArrayList<>();
    if (info.getNameRelations() != null) {
      for (NameUsageRelation rel : info.getNameRelations()) {
        if (nameId != null && rel.getNameId() != null && !nameId.equals(rel.getNameId())) continue;
        Name related = info.getNames() == null || rel.getRelatedNameId() == null
            ? null : info.getNames().get(rel.getRelatedNameId());
        String relatedLabel = related == null ? rel.getRelatedNameId() : label(related);
        String type = rel.getType() == null ? null : lower(rel.getType().name()).replace('_', ' ');
        relations.add(new ClbNameRelation(rel.getRelatedUsageId() + "|" + type, type, relatedLabel,
            related == null ? null : related.getScientificName()));
      }
    }

    Reference pub = info.getPublishedIn();
    return new ClbComparison(datasetKey, datasetTitle, u.getId(), link,
        n == null ? null : n.getScientificName(), n == null ? null : n.getAuthorship(),
        lower(n == null || n.getRank() == null ? null : n.getRank().name()),
        u.getStatus() == null ? null : u.getStatus().name(), acceptedName, classification, synonyms,
        vernaculars,
        n == null ? null : n.getEtymology(),
        n == null || n.getGender() == null ? null : lower(n.getGender().name()),
        pub == null ? null : pub.getCitation(),
        n == null ? null : n.getPublishedInPage(),
        types, relations);
  }

  private static void addSyns(List<ClbSynonym> out, List<Synonym> syns) {
    if (syns == null) return;
    for (Synonym s : syns) {
      Name sn = s.getName();
      if (sn == null) continue;
      out.add(new ClbSynonym(sn.getScientificName(), sn.getAuthorship(),
          s.getStatus() == null ? null : s.getStatus().name(), s.getId()));
    }
  }

  private static String label(Name n) {
    return n.getAuthorship() == null || n.getAuthorship().isBlank()
        ? n.getScientificName() : n.getScientificName() + " " + n.getAuthorship();
  }

  private static String lower(String s) {
    return s == null ? null : s.toLowerCase(Locale.ROOT);
  }
}
