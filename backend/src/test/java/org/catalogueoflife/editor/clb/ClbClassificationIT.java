package org.catalogueoflife.editor.clb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import life.catalogue.api.model.Name;
import life.catalogue.api.model.SimpleName;
import life.catalogue.api.model.Synonym;
import life.catalogue.api.model.Taxon;
import life.catalogue.api.model.UsageInfo;
import life.catalogue.api.vocab.TaxonomicStatus;
import org.catalogueoflife.editor.clb.dto.ClbClassificationPreview;
import org.catalogueoflife.editor.clb.dto.ClbClassificationRequest;
import org.catalogueoflife.editor.clb.dto.ClbClassificationResult;
import org.catalogueoflife.editor.clb.dto.ClbClassificationStep;
import org.catalogueoflife.editor.name.IdSeqMapper;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.dto.CreateProjectRequest;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.gbif.nameparser.api.Rank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

// ClbClassificationService ("wire into tree") against a real project/tree; CLB itself is mocked.
class ClbClassificationIT extends AbstractPostgresIT {

  @Autowired ProjectService projectService;
  @Autowired AppUserMapper users;
  @Autowired NameUsageMapper usages;
  @Autowired IdSeqMapper idSeq;
  @Autowired ClbClassificationService service;

  @MockitoBean ClbImportClient clb;

  private int createUser(String username) {
    AppUser u = new AppUser();
    u.setUsername(username);
    users.insert(u);
    return u.getId();
  }

  private int usage(int pid, int userId, String name, String rank, Integer parentId) {
    NameUsage u = new NameUsage();
    u.setProjectId(pid);
    u.setId(idSeq.allocate(pid, "name_usage"));
    u.setStatus(Status.ACCEPTED);
    u.setScientificName(name);
    u.setRank(rank);
    u.setParentId(parentId);
    u.setModifiedBy(userId);
    usages.insert(u);
    return u.getId();
  }

  private static SimpleName sn(String id, String name, Rank rank) {
    SimpleName s = new SimpleName(id, name, rank);
    return s;
  }

  // Panthera leo (T) in CLB: Animalia > Carnivora > Felidae > Panthera > [itself]
  private static UsageInfo leo() {
    Name n = new Name();
    n.setId("T-N");
    n.setScientificName("Panthera leo");
    n.setRank(Rank.SPECIES);
    Taxon t = new Taxon(n);
    t.setId("T");
    t.setStatus(TaxonomicStatus.ACCEPTED);
    UsageInfo info = new UsageInfo(t);
    info.setClassification(List.of(sn("A", "Animalia", Rank.KINGDOM), sn("C", "Carnivora", Rank.ORDER),
        sn("F", "Felidae", Rank.FAMILY), sn("G", "Panthera", Rank.GENUS), sn("T", "Panthera leo", Rank.SPECIES)));
    return info;
  }

  @Test
  void previewMatchesExistingRanksAndApplyCreatesTheChosenOnesAndMoves() {
    int userId = createUser("clb-cls");
    Project p = projectService.create(userId, new CreateProjectRequest("clb-cls", null, "zoological"));
    int pid = p.getId();
    int felidae = usage(pid, userId, "Felidae", "family", null);
    int focal = usage(pid, userId, "Panthera leo", "species", null);
    when(clb.usageInfo("3LXR", "T")).thenReturn(leo());

    ClbClassificationPreview preview = service.preview(userId, pid, focal, "3LXR", "T");
    assertThat(preview.steps()).extracting(ClbClassificationStep::name)
        .containsExactly("Animalia", "Carnivora", "Felidae", "Panthera"); // its own entry dropped
    assertThat(preview.steps()).extracting(ClbClassificationStep::matchId)
        .containsExactly(null, null, felidae, null);
    assertThat(preview.currentParentId()).isNull();

    ClbClassificationResult r = service.apply(userId, pid, focal,
        new ClbClassificationRequest("3LXR", "T", List.of("G")));
    assertThat(r.created()).isEqualTo(1);
    assertThat(r.moved()).isTrue();

    NameUsage panthera = usages.findByIdInProject(pid, r.parentId());
    assertThat(panthera.getScientificName()).isEqualTo("Panthera");
    assertThat(panthera.getRank()).isEqualTo("genus");
    assertThat(panthera.getParentId()).isEqualTo(felidae); // created under the existing family
    assertThat(panthera.getAlternativeId()).containsExactly("col:G");
    assertThat(usages.findByIdInProject(pid, focal).getParentId()).isEqualTo(panthera.getId());
    assertThat(usages.findAcceptedByNameAndRank(pid, "Animalia", "kingdom")).isEmpty(); // not chosen

    // Idempotent: a second run finds Panthera, creates nothing, moves nothing.
    ClbClassificationResult again = service.apply(userId, pid, focal,
        new ClbClassificationRequest("3LXR", "T", List.of("G")));
    assertThat(again.created()).isZero();
    assertThat(again.moved()).isFalse();
  }

  @Test
  void ranksAboveAnExistingMatchAreNotCreated() {
    int userId = createUser("clb-cls-above");
    int pid = projectService.create(userId, new CreateProjectRequest("clb-cls-above", null, "zoological")).getId();
    int felidae = usage(pid, userId, "Felidae", "family", null);
    int focal = usage(pid, userId, "Panthera leo", "species", null);
    when(clb.usageInfo("3LXR", "T")).thenReturn(leo());

    // Animalia sits above the existing Felidae, which is never moved -- creating it would only leave
    // an empty branch, so it is ignored; Panthera (below Felidae) is created.
    ClbClassificationResult r = service.apply(userId, pid, focal,
        new ClbClassificationRequest("3LXR", "T", List.of("A", "G")));
    assertThat(r.created()).isEqualTo(1);
    assertThat(usages.findAcceptedByNameAndRank(pid, "Animalia", "kingdom")).isEmpty();
    assertThat(usages.findByIdInProject(pid, r.parentId()).getParentId()).isEqualTo(felidae);
  }

  @Test
  void aDescendantOfTheFocalIsNeverUsedAsItsAncestor() {
    int userId = createUser("clb-cls-cycle");
    int pid = projectService.create(userId, new CreateProjectRequest("clb-cls-cycle", null, "zoological")).getId();
    // our "Panthera leo" (oddly) has a child named like a CLB ancestor
    int focal = usage(pid, userId, "Panthera leo", "species", null);
    usage(pid, userId, "Felidae", "family", focal);
    when(clb.usageInfo("3LXR", "T")).thenReturn(leo());

    ClbClassificationPreview preview = service.preview(userId, pid, focal, "3LXR", "T");
    assertThat(preview.steps()).allSatisfy(s -> assertThat(s.matchId()).isNull());
  }

  @Test
  void aSynonymTargetIsRejected() {
    int userId = createUser("clb-cls-syn");
    int pid = projectService.create(userId, new CreateProjectRequest("clb-cls-syn", null, "zoological")).getId();
    int focal = usage(pid, userId, "Panthera leo", "species", null);
    Name n = new Name();
    n.setScientificName("Felis leo");
    Synonym s = new Synonym(n);
    s.setId("S");
    s.setStatus(TaxonomicStatus.SYNONYM);
    when(clb.usageInfo("3LXR", "S")).thenReturn(new UsageInfo(s));

    assertThatThrownBy(() -> service.preview(userId, pid, focal, "3LXR", "S"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("synonym");
  }
}
