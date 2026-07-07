package org.catalogueoflife.editor.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
class OrcidUserServiceIT extends AbstractPostgresIT {

  @Autowired AppUserService users;
  @Autowired AppUserMapper mapper;

  @Test
  void upsertCreatesThenUpdatesByOrcid() {
    String orcid = "0000-0002-1111-2222";

    AppUser created = users.upsertFromOrcid(orcid, "Jane A. Smith", "Jane A.", "Smith");
    assertThat(created.getId()).isNotNull();
    assertThat(mapper.findByOrcid(orcid).getDisplayName()).isEqualTo("Jane A. Smith");

    AppUser updated = users.upsertFromOrcid(orcid, "Jane Anne Smith", "Jane Anne", "Smith");
    assertThat(updated.getId()).isEqualTo(created.getId());
    assertThat(mapper.findByOrcid(orcid).getDisplayName()).isEqualTo("Jane Anne Smith");
  }
}
