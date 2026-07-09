package org.catalogueoflife.editor.name;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import life.catalogue.api.vocab.Environment;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectMapper;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TaxonInfoMapperIT extends AbstractPostgresIT {

  private static final String ENTITY = "name_usage";

  @Autowired ProjectMapper projects;
  @Autowired AppUserMapper users;
  @Autowired NameUsageMapper nameUsages;
  @Autowired TaxonInfoMapper taxonInfo;
  @Autowired IdSeqMapper idSeq;

  @Test
  void upsertOverwriteDeleteAndCascade() {
    AppUser user = new AppUser();
    user.setUsername("ti-mapper-editor");
    users.insert(user);
    Project p = new Project();
    p.setTitle("TIMapper");
    projects.insert(p);
    int projectId = p.getId();

    NameUsage u = new NameUsage();
    u.setProjectId(projectId);
    u.setId(idSeq.allocate(projectId, ENTITY));
    u.setStatus(Status.ACCEPTED);
    u.setScientificName("Abies alba");
    u.setRank("species");
    u.setModifiedBy(user.getId());
    nameUsages.insert(u);

    // upsert + overwrite (same PK) then delete
    taxonInfo.upsert(projectId, u.getId(), true, List.of(Environment.MARINE), "Jurassic", null);
    assertThat(taxonInfo.count(projectId, u.getId())).isEqualTo(1);
    taxonInfo.upsert(projectId, u.getId(), false, List.of(Environment.TERRESTRIAL), null, "Holocene");
    assertThat(taxonInfo.count(projectId, u.getId())).isEqualTo(1);
    taxonInfo.delete(projectId, u.getId());
    assertThat(taxonInfo.count(projectId, u.getId())).isEqualTo(0);

    // ON DELETE CASCADE: deleting the usage removes any taxon_info row
    taxonInfo.upsert(projectId, u.getId(), true, null, null, null);
    assertThat(taxonInfo.count(projectId, u.getId())).isEqualTo(1);
    assertThat(nameUsages.delete(projectId, u.getId())).isEqualTo(1);
    assertThat(taxonInfo.count(projectId, u.getId())).isEqualTo(0);
  }
}
