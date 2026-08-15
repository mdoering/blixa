package org.catalogueoflife.editor.user;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
class ApplicationNoteMapperIT extends AbstractPostgresIT {

  @Autowired AppUserMapper mapper;

  @Test
  void persistsAndUpdatesApplicationNote() {
    AppUser u = new AppUser();
    u.setUsername("noteUser");
    u.setDisplayName("Note User");
    u.setState("PENDING");
    u.setApplicationNote("please let me in");
    mapper.insert(u);

    assertEquals("please let me in", mapper.findById(u.getId()).getApplicationNote());

    AppUser back = mapper.findById(u.getId());
    back.setApplicationNote("updated note");
    mapper.update(back);
    assertEquals("updated note", mapper.findById(u.getId()).getApplicationNote());
  }
}
