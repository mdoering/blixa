package org.catalogueoflife.editor.coldp.imprt;

import static org.assertj.core.api.Assertions.assertThat;

import org.catalogueoflife.editor.coldp.imprt.dto.ImportRunResponse;
import org.catalogueoflife.editor.coldp.imprt.dto.ImportRunResponse.ImportIssue;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectMapper;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.gbif.nameparser.api.NomCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

// Mirrors AppUserMapperIT/ProjectMapperIT's shape (a plain mapper-level IT against the real,
// shared testcontainers Postgres -- see AbstractPostgresIT) rather than ExportRunApiIT's
// MockMvc-driven one: Task 1 adds no controller, so this exercises ImportRunMapper directly.
// Each test creates its own app_user (import_run_user_idx / findLatestByUser scope by user_id),
// so tests don't interfere with each other despite sharing one DB for the whole JVM's test run.
class ImportRunMapperIT extends AbstractPostgresIT {

  @Autowired ImportRunMapper runs;
  @Autowired AppUserMapper users;
  @Autowired ProjectMapper projects;

  private Integer newUser(String username) {
    AppUser u = new AppUser();
    u.setUsername(username);
    users.insert(u);
    return u.getId();
  }

  private ImportRun newRunning(int userId, String sourceName) {
    ImportRun run = new ImportRun();
    run.setUserId(userId);
    run.setSourceName(sourceName);
    run.setPreserveIds(true);
    run.setIdScope("col");
    runs.insertRunning(run);
    return run;
  }

  @Test
  void insertRunningThenFindById() {
    int userId = newUser("importRunOwner1");
    ImportRun run = newRunning(userId, "archive.zip");
    assertThat(run.getId()).isNotNull();

    ImportRun found = runs.findById(run.getId());
    assertThat(found).isNotNull();
    assertThat(found.getUserId()).isEqualTo(userId);
    assertThat(found.getStatus()).isEqualTo("RUNNING");
    assertThat(found.getSourceName()).isEqualTo("archive.zip");
    assertThat(found.getPreserveIds()).isTrue();
    assertThat(found.getIdScope()).isEqualTo("col");
    assertThat(found.getProjectId()).isNull();
    assertThat(found.getNameUsageCount()).isZero();
    assertThat(found.getReferenceCount()).isZero();
    assertThat(found.getAuthorCount()).isZero();
    assertThat(found.getIssues()).isNull();
    assertThat(found.getStartedAt()).isNotNull();
    assertThat(found.getFinishedAt()).isNull();
    assertThat(found.getError()).isNull();
  }

  @Test
  void setProjectAttachesTheCreatedProject() {
    int userId = newUser("importRunOwner2");
    ImportRun run = newRunning(userId, "archive2.zip");

    Project p = new Project();
    p.setTitle("importedProject");
    p.setNomCode(NomCode.ZOOLOGICAL);
    projects.insert(p);
    assertThat(p.getId()).isNotNull();

    int updated = runs.setProject(run.getId(), p.getId());
    assertThat(updated).isEqualTo(1);

    ImportRun found = runs.findById(run.getId());
    assertThat(found.getProjectId()).isEqualTo(p.getId().longValue());
  }

  @Test
  void finishSetsCountsStatusDoneAndStoresIssuesAsRetrievableJson() throws Exception {
    int userId = newUser("importRunOwner3");
    ImportRun run = newRunning(userId, "archive3.zip");

    String issuesJson =
        "[{\"entity\":\"Name\",\"sourceId\":\"src-1\",\"message\":\"unparsable rank\"}]";
    int updated = runs.finish(run.getId(), 10, 5, 3, issuesJson);
    assertThat(updated).isEqualTo(1);

    ImportRun found = runs.findById(run.getId());
    assertThat(found.getStatus()).isEqualTo("DONE");
    assertThat(found.getNameUsageCount()).isEqualTo(10);
    assertThat(found.getReferenceCount()).isEqualTo(5);
    assertThat(found.getAuthorCount()).isEqualTo(3);
    assertThat(found.getFinishedAt()).isNotNull();
    assertThat(found.getIssues()).isNotNull();

    // Round-trip through ImportRunResponse.of exactly as an API layer would, proving the JSONB
    // column is retrievable as the same list of ImportIssue it was written with.
    ImportRunResponse response = ImportRunResponse.of(found, new ObjectMapper());
    assertThat(response.issues()).containsExactly(new ImportIssue("Name", "src-1", "unparsable rank"));
  }

  @Test
  void finishWithNullIssuesParsesToEmptyList() {
    int userId = newUser("importRunOwner3b");
    ImportRun run = newRunning(userId, "archive3b.zip");

    runs.finish(run.getId(), 1, 0, 0, null);

    ImportRun found = runs.findById(run.getId());
    assertThat(found.getIssues()).isNull();
    ImportRunResponse response = ImportRunResponse.of(found, new ObjectMapper());
    assertThat(response.issues()).isEmpty();
  }

  @Test
  void failSetsFailedStatusAndError() {
    int userId = newUser("importRunOwner4");
    ImportRun run = newRunning(userId, "archive4.zip");

    int updated = runs.fail(run.getId(), "boom: could not parse archive");
    assertThat(updated).isEqualTo(1);

    ImportRun found = runs.findById(run.getId());
    assertThat(found.getStatus()).isEqualTo("FAILED");
    assertThat(found.getError()).isEqualTo("boom: could not parse archive");
    assertThat(found.getFinishedAt()).isNotNull();
  }

  @Test
  void findLatestByUserReturnsTheMostRecentRunForThatUser() {
    int userId = newUser("importRunOwner5");
    int otherUserId = newUser("importRunOwner5other");

    ImportRun first = newRunning(userId, "first.zip");
    ImportRun second = newRunning(userId, "second.zip");
    newRunning(otherUserId, "unrelated.zip");

    ImportRun latest = runs.findLatestByUser(userId);
    assertThat(latest.getId()).isEqualTo(second.getId());
    assertThat(latest.getId()).isNotEqualTo(first.getId());
    assertThat(latest.getSourceName()).isEqualTo("second.zip");
  }

  @Test
  void failStaleRunningFlipsRunningRowsToFailed() {
    int userId = newUser("importRunOwner6");
    ImportRun run = newRunning(userId, "stale.zip");
    assertThat(runs.findById(run.getId()).getStatus()).isEqualTo("RUNNING");

    int reconciled = runs.failStaleRunning();
    assertThat(reconciled).isGreaterThanOrEqualTo(1);

    ImportRun found = runs.findById(run.getId());
    assertThat(found.getStatus()).isEqualTo("FAILED");
    assertThat(found.getError()).isEqualTo("interrupted by restart");
    assertThat(found.getFinishedAt()).isNotNull();
  }
}
