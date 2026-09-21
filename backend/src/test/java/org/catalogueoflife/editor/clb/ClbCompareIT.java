package org.catalogueoflife.editor.clb;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import life.catalogue.api.model.Name;
import life.catalogue.api.model.SimpleName;
import life.catalogue.api.model.Synonym;
import life.catalogue.api.model.Synonymy;
import life.catalogue.api.model.Taxon;
import life.catalogue.api.model.UsageInfo;
import life.catalogue.api.vocab.TaxonomicStatus;
import org.catalogueoflife.editor.clb.ClbImportClient.ClbGlobalUsageHit;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUserService;
import org.gbif.nameparser.api.Rank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClbCompareIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @MockitoBean ClbImportClient clb;

  private void ensureUser(String u) {
    if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u);
  }

  @Test
  @WithMockUser(username = "cmpUser")
  void compareMapsUsageInfo() throws Exception {
    ensureUser("cmpUser");

    Name n = new Name();
    n.setScientificName("Panthera leo");
    n.setAuthorship("(Linnaeus, 1758)");
    n.setRank(Rank.SPECIES);
    Taxon t = new Taxon(n);
    t.setId("6W3C4");
    t.setStatus(TaxonomicStatus.ACCEPTED);
    UsageInfo info = new UsageInfo(t);
    SimpleName family = new SimpleName();
    family.setName("Felidae");
    family.setRank(Rank.FAMILY);
    info.setClassification(List.of(family));

    Name sn = new Name();
    sn.setScientificName("Felis leo");
    sn.setAuthorship("Linnaeus, 1758");
    sn.setRank(Rank.SPECIES);
    Synonym s = new Synonym(sn);
    s.setStatus(TaxonomicStatus.SYNONYM);
    Synonymy synonymy = new Synonymy();
    synonymy.getHeterotypic().add(s);
    info.setSynonyms(synonymy);

    when(clb.usageInfo(eq("3LXR"), eq("6W3C4"))).thenReturn(info);
    when(clb.datasetTitle(eq("3LXR"))).thenReturn("Catalogue of Life");

    mvc.perform(get("/api/clb/3LXR/compare/6W3C4"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.datasetKey").value("3LXR"))
       .andExpect(jsonPath("$.datasetTitle").value("Catalogue of Life"))
       .andExpect(jsonPath("$.scientificName").value("Panthera leo"))
       .andExpect(jsonPath("$.authorship").value("(Linnaeus, 1758)"))
       .andExpect(jsonPath("$.rank").value("species"))
       .andExpect(jsonPath("$.status").value("ACCEPTED"))
       .andExpect(jsonPath("$.classification[0].rank").value("family"))
       .andExpect(jsonPath("$.classification[0].name").value("Felidae"))
       .andExpect(jsonPath("$.synonyms[0].scientificName").value("Felis leo"))
       .andExpect(jsonPath("$.synonyms[0].status").value("SYNONYM"));
  }

  @Test
  @WithMockUser(username = "cmpUser2")
  void globalUsageSearchProxiesClient() throws Exception {
    ensureUser("cmpUser2");
    when(clb.searchUsagesAllDatasets(eq("Panthera leo"), isNull()))
        .thenReturn(List.of(new ClbGlobalUsageHit("3LXR", "Catalogue of Life", "6W3C4",
            "Panthera leo", "(Linnaeus, 1758)", "species", "accepted")));

    mvc.perform(get("/api/clb/usages").param("q", "Panthera leo"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$[0].datasetKey").value("3LXR"))
       .andExpect(jsonPath("$[0].datasetTitle").value("Catalogue of Life"))
       .andExpect(jsonPath("$[0].scientificName").value("Panthera leo"));
  }

  @Test
  @WithMockUser(username = "cmpUser3")
  void globalUsageSearchHidesPrivateDatasetsAndFillsTitles() throws Exception {
    ensureUser("cmpUser3");
    when(clb.searchUsagesAllDatasets(eq("Anoiapithecus"), isNull()))
        .thenReturn(List.of(
            new ClbGlobalUsageHit("201890", null, "3607727", "Anoiapithecus", null, "unranked", "accepted"),
            new ClbGlobalUsageHit("310869", null, "4457929", "Anoiapithecus", null, "unranked", "accepted")));
    when(clb.dataset(eq("201890"))).thenReturn(new ClbImportClient.ClbDatasetRef("Paleobiology Database", "PBDB", true));
    when(clb.dataset(eq("310869"))).thenReturn(ClbImportClient.ClbDatasetRef.INACCESSIBLE);

    mvc.perform(get("/api/clb/usages").param("q", "Anoiapithecus"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.length()").value(1))
       .andExpect(jsonPath("$[0].datasetKey").value("201890"))
       .andExpect(jsonPath("$[0].datasetTitle").value("Paleobiology Database"));
  }

  @Test
  @WithMockUser(username = "cmpUser4")
  void compareOnPrivateDatasetIs403() throws Exception {
    ensureUser("cmpUser4");
    when(clb.usageInfo(eq("310869"), eq("4457929")))
        .thenThrow(new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.FORBIDDEN, "This ChecklistBank dataset is private"));

    mvc.perform(get("/api/clb/310869/compare/4457929")).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(username = "cmpUser5")
  void compareASynonymShowsItsAcceptedNameAndNameusageLink() throws Exception {
    ensureUser("cmpUser5");
    Name an = new Name();
    an.setScientificName("Panthera leo");
    an.setAuthorship("(Linnaeus, 1758)");
    an.setRank(Rank.SPECIES);
    Taxon acc = new Taxon(an);
    acc.setId("4CGXP");
    Name sn = new Name();
    sn.setScientificName("Felis leo");
    sn.setAuthorship("Linnaeus, 1758");
    sn.setRank(Rank.SPECIES);
    Synonym syn = new Synonym(sn);
    syn.setId("CFSCR");
    syn.setStatus(TaxonomicStatus.SYNONYM);
    syn.setAccepted(acc);
    when(clb.usageInfo(eq("3LXR"), eq("CFSCR"))).thenReturn(new UsageInfo(syn));
    when(clb.datasetTitle(eq("3LXR"))).thenReturn("Catalogue of Life");

    mvc.perform(get("/api/clb/3LXR/compare/CFSCR"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.scientificName").value("Felis leo"))
       .andExpect(jsonPath("$.status").value("SYNONYM"))
       .andExpect(jsonPath("$.acceptedName").value("Panthera leo (Linnaeus, 1758)"))
       .andExpect(jsonPath("$.link").value("https://www.checklistbank.org/dataset/3LXR/nameusage/CFSCR"));
  }

  @Test
  @WithMockUser(username = "cmpUser6")
  void comparisonCarriesEtymologyGenderPublishedInTypesRelationsAndVernaculars() throws Exception {
    ensureUser("cmpUser6");
    Name n = new Name();
    n.setId("N1");
    n.setScientificName("Panthera");
    n.setAuthorship("Oken, 1816");
    n.setRank(Rank.GENUS);
    n.setEtymology("from Greek panther");
    n.setGender(life.catalogue.api.vocab.Gender.FEMININE);
    n.setPublishedInPage("1052");
    Taxon t = new Taxon(n);
    t.setId("6DBT");
    t.setStatus(TaxonomicStatus.ACCEPTED);
    UsageInfo info = new UsageInfo(t);
    life.catalogue.api.model.Reference pub = new life.catalogue.api.model.Reference();
    pub.setId("PUB");
    pub.setCitation("Oken, Lehrbuch 1816");
    info.setPublishedIn(pub);
    life.catalogue.api.model.TypeMaterial tm = new life.catalogue.api.model.TypeMaterial();
    tm.setId("TM1");
    tm.setCitation("type species: Felis pardus");
    info.getTypeMaterial().put("N1", new java.util.ArrayList<>(List.of(tm)));
    life.catalogue.api.model.VernacularName vn = new life.catalogue.api.model.VernacularName();
    vn.setId(7);
    vn.setName("Big cats");
    vn.setLanguage("eng");
    info.setVernacularNames(List.of(vn));
    Name bas = new Name();
    bas.setId("N2");
    bas.setScientificName("Pantherus");
    bas.setAuthorship("Smith");
    life.catalogue.api.model.NameUsageRelation rel = new life.catalogue.api.model.NameUsageRelation();
    rel.setNameId("N1");
    rel.setRelatedNameId("N2");
    rel.setType(life.catalogue.api.vocab.NomRelType.BASIONYM);
    info.setNameRelations(List.of(rel));
    info.getNames().put("N2", bas);

    when(clb.usageInfo(eq("3LXR"), eq("6DBT"))).thenReturn(info);
    when(clb.datasetTitle(eq("3LXR"))).thenReturn("Catalogue of Life");

    mvc.perform(get("/api/clb/3LXR/compare/6DBT"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.etymology").value("from Greek panther"))
       .andExpect(jsonPath("$.gender").value("feminine"))
       .andExpect(jsonPath("$.publishedIn").value("Oken, Lehrbuch 1816"))
       .andExpect(jsonPath("$.publishedInPage").value("1052"))
       .andExpect(jsonPath("$.typeMaterial[0].id").value("TM1"))
       .andExpect(jsonPath("$.typeMaterial[0].citation").value("type species: Felis pardus"))
       .andExpect(jsonPath("$.nameRelations[0].type").value("basionym"))
       .andExpect(jsonPath("$.nameRelations[0].relatedName").value("Pantherus Smith"))
       .andExpect(jsonPath("$.vernacularNames[0].id").value("7"))
       .andExpect(jsonPath("$.vernacularNames[0].name").value("Big cats"));
  }
}
