package org.catalogueoflife.editor;

import org.catalogueoflife.editor.user.AppUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// Dev-only convenience: seed a local login account so a fresh database is immediately usable through
// the UI. There is no self-service signup, and ORCID login needs real credentials, so without this a
// brand-new local DB has no way to authenticate. Active ONLY under the "dev" Spring profile -- it
// never runs in production or under the "test" profile the integration tests use. Idempotent.
// @Order(0): must run before DevSampleData (@Order(1)), which seeds a sample project owned by this
// user and so needs the account to already exist on first boot.
@Component
@Profile("dev")
@Order(0)
public class DevBootstrap implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(DevBootstrap.class);

  private final AppUserService users;
  private final String username;
  private final String password;
  private final String displayName;

  public DevBootstrap(AppUserService users,
      @Value("${editor.dev.username:admin}") String username,
      @Value("${editor.dev.password:admin}") String password,
      @Value("${editor.dev.display-name:Local Admin}") String displayName) {
    this.users = users;
    this.username = username;
    this.password = password;
    this.displayName = displayName;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (users.requireByUsernameOrNull(username) != null) {
      log.info("Dev login user '{}' already present", username);
      return;
    }
    users.createLocal(username, password, displayName);
    log.warn("DEV PROFILE: seeded local login user '{}' (password '{}'). "
        + "Never enable the 'dev' profile in production.", username, password);
  }
}
