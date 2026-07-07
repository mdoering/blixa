package org.catalogueoflife.editor.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ProjectMapperIT extends AbstractPostgresIT {

  @Autowired ProjectMapper projects;
  @Autowired ProjectMemberMapper members;
  @Autowired AppUserMapper users;

  @Test
  void createProjectWithOwnerAndListByMember() {
    AppUser u = new AppUser();
    u.setUsername("owner1");
    users.insert(u);

    Project p = new Project();
    p.setSlug("lepidoptera");
    p.setTitle("Lepidoptera");
    p.setNomCode("zoological");
    projects.insert(p);
    assertThat(p.getId()).isNotNull();

    members.upsert(new ProjectMember(p.getId(), u.getId(), Role.OWNER.dbValue()));

    assertThat(members.findRole(p.getId(), u.getId())).isEqualTo("owner");
    List<Project> mine = projects.findByMember(u.getId());
    assertThat(mine).extracting(Project::getSlug).containsExactly("lepidoptera");
  }
}
