package org.catalogueoflife.editor.name;

import static org.assertj.core.api.Assertions.assertThat;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class IdSeqMapperIT extends AbstractPostgresIT {

  @Autowired IdSeqMapper idSeq;

  @Test
  void allocatesSequentiallyPerProjectAndEntity() {
    assertThat(idSeq.allocate(9001, "widget")).isEqualTo(1);
    assertThat(idSeq.allocate(9001, "widget")).isEqualTo(2);
    assertThat(idSeq.allocate(9001, "widget")).isEqualTo(3);

    // a different entity in the same project starts its own sequence at 1
    assertThat(idSeq.allocate(9001, "gadget")).isEqualTo(1);

    // a different project's "widget" sequence is independent too
    assertThat(idSeq.allocate(9002, "widget")).isEqualTo(1);
    assertThat(idSeq.allocate(9002, "widget")).isEqualTo(2);

    // back to the first project+entity: continues from where it left off
    assertThat(idSeq.allocate(9001, "widget")).isEqualTo(4);
  }
}
