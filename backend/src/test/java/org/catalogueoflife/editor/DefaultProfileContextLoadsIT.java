package org.catalogueoflife.editor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

// Loads the full application context under the DEFAULT profile (NOT "test"), so the
// main application.yml's ORCID registration is exercised with ORCID_CLIENT_ID unset.
// Guards against the empty-default client-id that previously crashed startup with
// "clientId cannot be empty". Uses its own container because AbstractPostgresIT forces
// @ActiveProfiles("test").
@SpringBootTest
class DefaultProfileContextLoadsIT {

  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

  static {
    POSTGRES.start();
  }

  @Test
  void contextLoads() {
    // Passes iff the Spring context (incl. the ORCID ClientRegistrationRepository)
    // initializes without ORCID credentials configured.
  }
}
