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
}
