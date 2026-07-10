package org.catalogueoflife.editor;

import org.catalogueoflife.editor.name.NameUsageService;
import org.catalogueoflife.editor.name.Reference;
import org.catalogueoflife.editor.name.ReferenceService;
import org.catalogueoflife.editor.name.dto.CreateNameUsageRequest;
import org.catalogueoflife.editor.name.dto.CreateReferenceRequest;
import org.catalogueoflife.editor.name.dto.NameUsageResponse;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.dto.CreateProjectRequest;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// Dev-only convenience: seed one small, real classification so a fresh database shows something
// clickable in the editor (tree + names search + a synonym list + a reference) instead of an empty
// project list. Without this, DevBootstrap gives you a login but nothing to browse. Active ONLY
// under the "dev" Spring profile -- never in production or the "test" profile the ITs use.
//
// @Order(1) runs this AFTER DevBootstrap (@Order(0)), so the login user it owns the project as
// already exists on first boot. Idempotent: keyed on the sample project's title, so re-runs (and a
// persistent dev DB) never duplicate it. A partial-seed failure is logged, not fatal -- it must not
// block dev startup.
@Component
@Profile("dev")
@Order(1)
public class DevSampleData implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(DevSampleData.class);
  private static final String SAMPLE_TITLE = "Felidae (sample data)";

  private final AppUserService users;
  private final ProjectService projects;
  private final ReferenceService references;
  private final NameUsageService usages;
  private final TransactionTemplate tx;
  private final String username;

  public DevSampleData(AppUserService users, ProjectService projects, ReferenceService references,
      NameUsageService usages, PlatformTransactionManager txManager,
      @Value("${editor.dev.username:admin}") String username) {
    this.users = users;
    this.projects = projects;
    this.references = references;
    this.usages = usages;
    this.tx = new TransactionTemplate(txManager);
    this.username = username;
  }

  @Override
  public void run(ApplicationArguments args) {
    AppUser admin = users.requireByUsernameOrNull(username);
    if (admin == null) {
      log.warn("DEV sample data: login user '{}' not found; skipping seed", username);
      return;
    }
    int userId = admin.getId();
    boolean present = projects.listForUser(userId).stream()
        .anyMatch(p -> SAMPLE_TITLE.equals(p.getTitle()));
    if (present) {
      log.info("DEV sample data: project '{}' already present; skipping seed", SAMPLE_TITLE);
      return;
    }
    try {
      // One transaction for the whole seed: the individual create/link service calls are each
      // @Transactional (they join this one), so a failure partway through rolls the ENTIRE seed back
      // -- including the project row itself. That keeps the title-based idempotency check above
      // honest: the project exists only if it was seeded in full, never as an empty shell.
      tx.executeWithoutResult(status -> seed(userId));
    } catch (RuntimeException e) {
      // Dev-only seeding must never take down the app -- a persistent DB in an odd state, a schema
      // drift, etc. Log and carry on; the editor still boots (just without the sample tree).
      log.warn("DEV sample data: seeding project '{}' failed; continuing without it", SAMPLE_TITLE, e);
    }
  }

  private void seed(int userId) {
    Project project = projects.create(userId, new CreateProjectRequest(SAMPLE_TITLE, "zoological"));
    int pid = project.getId();

    Reference linnaeus = references.create(userId, pid, new CreateReferenceRequest(
        "Linnaeus, C. (1758). Systema Naturae per regna tria naturae, 10th ed., vol. 1. "
            + "Laurentius Salvius, Stockholm.",
        "book", "Linnaeus, C.", null, "Systema Naturae per regna tria naturae",
        null, "1758", "1", null, "1-824", "Laurentius Salvius",
        null, null, null, "https://www.biodiversitylibrary.org/item/10277", null, null));
    int ref = linnaeus.getId();

    // Accepted classification, built strictly top-down so each parent is already an accepted usage
    // when its children reference it (NameUsageService.create validates the parent).
    int animalia = accepted(userId, pid, "Animalia", "Linnaeus, 1758", "kingdom", null, ref, 1758);
    int chordata = accepted(userId, pid, "Chordata", "Haeckel, 1874", "phylum", animalia, null, 1874);
    int mammalia = accepted(userId, pid, "Mammalia", "Linnaeus, 1758", "class", chordata, ref, 1758);
    int carnivora = accepted(userId, pid, "Carnivora", "Bowdich, 1821", "order", mammalia, null, 1821);
    int felidae = accepted(userId, pid, "Felidae", "Fischer de Waldheim, 1817", "family", carnivora, null, 1817);
    int panthera = accepted(userId, pid, "Panthera", "Oken, 1816", "genus", felidae, null, 1816);
    int felis = accepted(userId, pid, "Felis", "Linnaeus, 1758", "genus", felidae, ref, 1758);

    int leo = accepted(userId, pid, "Panthera leo", "(Linnaeus, 1758)", "species", panthera, ref, 1758);
    int tigris = accepted(userId, pid, "Panthera tigris", "(Linnaeus, 1758)", "species", panthera, ref, 1758);
    accepted(userId, pid, "Felis catus", "Linnaeus, 1758", "species", felis, ref, 1758);

    // Synonyms: the original combinations Linnaeus placed in Felis, now synonyms of the Panthera
    // species above. Created without a tree parent (synonyms don't sit in the classification), then
    // linked to their accepted usage via synonym_accepted.
    synonym(userId, pid, "Felis leo", "Linnaeus, 1758", "species", ref, 1758, leo);
    synonym(userId, pid, "Felis tigris", "Linnaeus, 1758", "species", ref, 1758, tigris);

    log.warn("DEV PROFILE: seeded sample project '{}' (id {}) -- a small Felidae classification "
        + "with two synonyms and a reference. Never enable the 'dev' profile in production.",
        SAMPLE_TITLE, pid);
  }

  private int accepted(int userId, int pid, String name, String authorship, String rank,
      Integer parentId, Integer refId, Integer year) {
    return create(userId, pid, name, authorship, rank, "ACCEPTED", parentId, refId, year).id();
  }

  private void synonym(int userId, int pid, String name, String authorship, String rank,
      Integer refId, Integer year, int acceptedId) {
    NameUsageResponse s = create(userId, pid, name, authorship, rank, "SYNONYM", null, refId, year);
    usages.linkSynonym(userId, pid, s.id(), acceptedId);
  }

  // Single positional CreateNameUsageRequest construction, kept here so the callers above stay
  // readable and don't each repeat the record's long null tail.
  private NameUsageResponse create(int userId, int pid, String name, String authorship, String rank,
      String status, Integer parentId, Integer refId, Integer year) {
    return usages.create(userId, pid, new CreateNameUsageRequest(
        name, authorship, rank, status, parentId,
        null,  // namePhrase
        null,  // nomStatus
        refId, // publishedInReferenceId
        year,  // publishedInYear
        null,  // publishedInPage
        null,  // publishedInPageLink
        null,  // gender
        null,  // extinct
        null,  // environment
        null,  // temporalRangeStart
        null,  // temporalRangeEnd
        null)); // remarks
  }
}
