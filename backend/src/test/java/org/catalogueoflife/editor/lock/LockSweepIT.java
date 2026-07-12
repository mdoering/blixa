package org.catalogueoflife.editor.lock;

import static org.assertj.core.api.Assertions.assertThat;

import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectMapper;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.gbif.nameparser.api.NomCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// Mapper-level IT for the retention sweep's deletion query (see LockRetentionSweep): a lock whose
// expires_at has lapsed must be physically removed by deleteExpired(), while a still-active lock
// in the same project is left untouched.
class LockSweepIT extends AbstractPostgresIT {

  @Autowired LockMapper locks;
  @Autowired ProjectMapper projects;
  @Autowired AppUserMapper users;

  @Test
  void deleteExpiredRemovesOnlyExpiredRows() {
    AppUser u = new AppUser();
    u.setUsername("sweepUser");
    users.insert(u);

    Project p = new Project();
    p.setTitle("SweepProject");
    p.setNomCode(NomCode.ZOOLOGICAL);
    projects.insert(p);

    int liveEntityId = 1;
    int expiredEntityId = 2;
    // live lock: ttl 300s, still active.
    locks.upsertTakeover(p.getId(), "name_usage", liveEntityId, u.getId(), null, 300);
    // expired lock: acquired normally, then hand-pushed into the past -- upsertTakeover's ttl is a
    // positive seconds-from-now offset, so a negative/expired expires_at can't be seeded through it.
    locks.upsertTakeover(p.getId(), "name_usage", expiredEntityId, u.getId(), null, 300);
    Lock expired = locks.findByEntity(p.getId(), "name_usage", expiredEntityId);
    locks.expireForTest(expired.getId());

    int removed = locks.deleteExpired();

    assertThat(removed).isEqualTo(1);
    assertThat(locks.findActive(p.getId())).extracting(Lock::getEntityId).containsExactly(liveEntityId);
    assertThat(locks.findByEntity(p.getId(), "name_usage", expiredEntityId)).isNull();
  }
}
