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

  @Autowired ProjectMapper projects;
  @Autowired AppUserMapper users;
  @Autowired NameUsageMapper nameUsages;
  @Autowired SynonymAcceptedMapper synonymAccepted;

  @Test
  void insertLinkAndQuerySynonymy() {
    AppUser u = new AppUser();
    u.setUsername("name-editor");
    users.insert(u);
    assertThat(u.getId()).isNotNull();

    Project p = new Project();
    p.setSlug("brassicaceae");
    p.setTitle("Brassicaceae");
    projects.insert(p);
    assertThat(p.getId()).isNotNull();

    NameUsage accepted = new NameUsage();
    accepted.setProjectId(p.getId());
    accepted.setColdpId("acc-1");
    accepted.setAlternativeId(List.of("alt-1", "alt-2"));
    accepted.setReferenceId(List.of(100L, 200L));
    accepted.setStatus("accepted");
    accepted.setScientificName("Abies alba");
    accepted.setAuthorship("Mill.");
    accepted.setRank("species");
    accepted.setModifiedBy(u.getId());
    nameUsages.insert(accepted);
    assertThat(accepted.getId()).isNotNull();

    NameUsage synonym = new NameUsage();
    synonym.setProjectId(p.getId());
    synonym.setColdpId("syn-1");
    synonym.setStatus("synonym");
    synonym.setScientificName("Pinus picea");
    synonym.setRank("species");
    synonym.setModifiedBy(u.getId());
    nameUsages.insert(synonym);
    assertThat(synonym.getId()).isNotNull();

    synonymAccepted.link(synonym.getId(), accepted.getId(), 0);
    // re-linking the same pair must be a no-op thanks to ON CONFLICT DO NOTHING
    synonymAccepted.link(synonym.getId(), accepted.getId(), 0);

    NameUsage found = nameUsages.findById(accepted.getId());
    assertThat(found.getScientificName()).isEqualTo("Abies alba");
    assertThat(found.getAlternativeId()).containsExactly("alt-1", "alt-2");
    assertThat(found.getReferenceId()).containsExactly(100L, 200L);

    NameUsage foundSynonym = nameUsages.findById(synonym.getId());
    assertThat(foundSynonym.getAlternativeId()).isNull();
    assertThat(foundSynonym.getReferenceId()).isNull();

    assertThat(synonymAccepted.findSynonymsOf(accepted.getId())).containsExactly(synonym.getId());
    assertThat(synonymAccepted.findAcceptedFor(synonym.getId())).containsExactly(accepted.getId());
  }
}
