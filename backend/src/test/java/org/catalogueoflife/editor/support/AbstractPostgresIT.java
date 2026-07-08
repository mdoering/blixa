package org.catalogueoflife.editor.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

// Singleton-container pattern: one postgres:17 container is started once (static
// initializer) and shared by every IT for the JVM's lifetime (Testcontainers' Ryuk
// stops it at the end). This avoids per-class start/stop and the context-cache vs
// container-lifecycle mismatches you get with @Testcontainers/@Container. @ServiceConnection
// wires Spring Boot's datasource to it.
//
// @ActiveProfiles("test") applies to ALL integration tests so that, once the ORCID
// client registration is added to the main application.yml in Task 7 (with an empty
// default client-id), every IT still loads its context using the dummy ORCID
// credentials from src/test/resources/application-test.yml (created in Task 4).
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractPostgresIT {

  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

  static {
    POSTGRES.start();
  }
}
