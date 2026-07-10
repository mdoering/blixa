package org.catalogueoflife.editor.coldp;

import static org.assertj.core.api.Assertions.assertThat;

import life.catalogue.api.model.Identifier;
import life.catalogue.coldp.ColdpTerm;
import life.catalogue.common.io.TabWriter;
import life.catalogue.csv.ColdpReader;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;

// Proves that org.catalogueoflife:reader (and its transitive api/coldp/dwc-api/name-parser
// dependencies, all Jackson 2 `com.fasterxml`) coexists on the classpath with our Spring Boot
// / Jackson 3 (`tools.jackson`) stack: the @SpringBootTest context in AbstractPostgresIT must
// still load cleanly, and the ColDP io types must be resolvable and usable at runtime.
class ColdpDependencyIT extends AbstractPostgresIT {

  @Test
  void coldpIoTypesAreOnClasspath() {
    assertThat(ColdpTerm.NameUsage.isClass()).isTrue();
    assertThat(ColdpTerm.RESOURCES.get(ColdpTerm.Reference)).contains(ColdpTerm.ID);
    assertThat(Identifier.Scope.COL.prefix()).isEqualTo("col");
    // TabWriter/ColdpReader are importable and instantiable at compile+run time.
    assertThat(TabWriter.class).isNotNull();
    assertThat(ColdpReader.class).isNotNull();
  }
}
