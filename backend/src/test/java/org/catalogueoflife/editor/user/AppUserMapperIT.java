package org.catalogueoflife.editor.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AppUserMapperIT extends AbstractPostgresIT {

  @Autowired AppUserMapper mapper;

  @Test
  void insertsAndReadsBackByOrcid() {
    AppUser u = new AppUser();
    u.setUsername("0000-0001-7757-1889");
    u.setOrcid("0000-0001-7757-1889");
    u.setDisplayName("Markus Döring");

    mapper.insert(u);

    assertThat(u.getId()).isNotNull();
    AppUser found = mapper.findByOrcid("0000-0001-7757-1889");
    assertThat(found).isNotNull();
    assertThat(found.getDisplayName()).isEqualTo("Markus Döring");
    assertThat(found.getId()).isEqualTo(u.getId());
  }
}
