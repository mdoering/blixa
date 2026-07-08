package org.catalogueoflife.editor.name;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectMapper;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class NameUsageMapperIT extends AbstractPostgresIT {

  private static final String ENTITY = "name_usage";

  @Autowired ProjectMapper projects;
  @Autowired AppUserMapper users;
  @Autowired NameUsageMapper nameUsages;
  @Autowired SynonymAcceptedMapper synonymAccepted;
  @Autowired IdSeqMapper idSeq;

  @Test
  void insertLinkAndQuerySynonymy() {
    AppUser u = new AppUser();
    u.setUsername("name-editor");
    users.insert(u);
    assertThat(u.getId()).isNotNull();

    Project p = new Project();
    p.setTitle("Brassicaceae");
    projects.insert(p);
    assertThat(p.getId()).isNotNull();
    int projectId = p.getId();

    NameUsage accepted = new NameUsage();
    accepted.setProjectId(projectId);
    accepted.setId(idSeq.allocate(projectId, ENTITY));
    accepted.setColdpId("acc-1");
    accepted.setAlternativeId(List.of("alt-1", "alt-2"));
    accepted.setReferenceId(List.of(100, 200));
    accepted.setStatus("accepted");
    accepted.setScientificName("Abies alba");
    accepted.setAuthorship("Mill.");
    accepted.setRank("species");
    accepted.setModifiedBy(u.getId());
    nameUsages.insert(accepted);
    assertThat(accepted.getId()).isEqualTo(1);

    NameUsage synonym = new NameUsage();
    synonym.setProjectId(projectId);
    synonym.setId(idSeq.allocate(projectId, ENTITY));
    synonym.setColdpId("syn-1");
    synonym.setStatus("synonym");
    synonym.setScientificName("Pinus picea");
    synonym.setRank("species");
    synonym.setModifiedBy(u.getId());
    nameUsages.insert(synonym);
    assertThat(synonym.getId()).isEqualTo(2);

    synonymAccepted.link(projectId, synonym.getId(), accepted.getId(), 0);
    // re-linking the same pair must be a no-op thanks to ON CONFLICT DO NOTHING
    synonymAccepted.link(projectId, synonym.getId(), accepted.getId(), 0);

    NameUsage found = nameUsages.findByIdInProject(projectId, accepted.getId());
    assertThat(found.getScientificName()).isEqualTo("Abies alba");
    assertThat(found.getAlternativeId()).containsExactly("alt-1", "alt-2");
    assertThat(found.getReferenceId()).containsExactly(100, 200);

    NameUsage foundSynonym = nameUsages.findByIdInProject(projectId, synonym.getId());
    assertThat(foundSynonym.getAlternativeId()).isNull();
    assertThat(foundSynonym.getReferenceId()).isNull();

    assertThat(synonymAccepted.findSynonymsOf(projectId, accepted.getId())).containsExactly(synonym.getId());
    assertThat(synonymAccepted.findAcceptedFor(projectId, synonym.getId())).containsExactly(accepted.getId());
  }

  @Test
  void optimisticUpdateDetectsStaleVersion() {
    AppUser u = new AppUser();
    u.setUsername("name-editor-cas");
    users.insert(u);
    assertThat(u.getId()).isNotNull();

    Project p = new Project();
    p.setTitle("CAS Project");
    projects.insert(p);
    assertThat(p.getId()).isNotNull();
    int projectId = p.getId();

    NameUsage nu = new NameUsage();
    nu.setProjectId(projectId);
    nu.setId(idSeq.allocate(projectId, ENTITY));
    nu.setColdpId("cas-1");
    nu.setStatus("accepted");
    nu.setScientificName("Abies alba");
    nu.setRank("species");
    nu.setModifiedBy(u.getId());
    nameUsages.insert(nu);
    assertThat(nu.getId()).isNotNull();

    NameUsage loaded = nameUsages.findByIdInProject(projectId, nu.getId());
    int loadedVersion = loaded.getVersion();

    // First update using the correct, just-loaded version succeeds.
    loaded.setScientificName("Abies alba subsp. alba");
    int rows = nameUsages.update(loaded);
    assertThat(rows).isEqualTo(1);

    NameUsage afterUpdate = nameUsages.findByIdInProject(projectId, nu.getId());
    assertThat(afterUpdate.getVersion()).isEqualTo(loadedVersion + 1);
    assertThat(afterUpdate.getScientificName()).isEqualTo("Abies alba subsp. alba");

    // Second update reusing the now-stale original version must be rejected.
    loaded.setScientificName("Abies alba subsp. concurrent-edit");
    int staleRows = nameUsages.update(loaded);
    assertThat(staleRows).isEqualTo(0);

    NameUsage unchanged = nameUsages.findByIdInProject(projectId, nu.getId());
    assertThat(unchanged.getVersion()).isEqualTo(loadedVersion + 1);
    assertThat(unchanged.getScientificName()).isEqualTo("Abies alba subsp. alba");
  }

  // Chunk 2 requirement: name_usage now uses a per-project compound (project_id, id) key with
  // an independent id_seq sequence per project, so the FIRST usage created in ANY project gets
  // id 1 -- it is not a global, ever-increasing identity column any more.
  @Test
  void idsAreSequentialPerProjectIndependently() {
    Project a = new Project();
    a.setTitle("Sequence Project A");
    projects.insert(a);
    int projectA = a.getId();

    Project b = new Project();
    b.setTitle("Sequence Project B");
    projects.insert(b);
    int projectB = b.getId();

    NameUsage firstInA = newMinimalUsage(projectA);
    firstInA.setId(idSeq.allocate(projectA, ENTITY));
    nameUsages.insert(firstInA);
    assertThat(firstInA.getId()).isEqualTo(1);

    NameUsage secondInA = newMinimalUsage(projectA);
    secondInA.setId(idSeq.allocate(projectA, ENTITY));
    nameUsages.insert(secondInA);
    assertThat(secondInA.getId()).isEqualTo(2);

    // a completely independent SECOND project starts its own sequence at 1 too
    NameUsage firstInB = newMinimalUsage(projectB);
    firstInB.setId(idSeq.allocate(projectB, ENTITY));
    nameUsages.insert(firstInB);
    assertThat(firstInB.getId()).isEqualTo(1);

    // and both rows are independently addressable via their compound key
    assertThat(nameUsages.findByIdInProject(projectA, 1).getId()).isEqualTo(1);
    assertThat(nameUsages.findByIdInProject(projectB, 1).getId()).isEqualTo(1);
    assertThat(nameUsages.findByIdInProject(projectA, 1).getProjectId()).isEqualTo(projectA);
    assertThat(nameUsages.findByIdInProject(projectB, 1).getProjectId()).isEqualTo(projectB);
  }

  private static NameUsage newMinimalUsage(int projectId) {
    NameUsage u = new NameUsage();
    u.setProjectId(projectId);
    u.setStatus("accepted");
    u.setScientificName("Abies alba");
    u.setRank("species");
    return u;
  }
}
