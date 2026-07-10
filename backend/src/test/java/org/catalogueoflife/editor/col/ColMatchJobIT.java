package org.catalogueoflife.editor.col;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import org.catalogueoflife.editor.name.ClbMatchClient;
import org.catalogueoflife.editor.name.IdSeqMapper;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectMapper;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.catalogueoflife.editor.validation.Issue;
import org.catalogueoflife.editor.validation.IssueMapper;
import org.gbif.nameparser.api.NomCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

// ColMatchJobService.matchOne/runSync: the synchronous, testable core of the project-wide bulk
// COL-match job. Entirely at the mapper/service level (like NameUsageMapperIT) -- no MockMvc/HTTP
// needed since there's no controller yet (Task 3). The external CLB call (ClbMatchClient) is
// entirely mocked -- never a real network call -- mirroring ColMatchIT.
class ColMatchJobIT extends AbstractPostgresIT {

  private static final String ENTITY = "name_usage";

  @Autowired ProjectMapper projects;
  @Autowired AppUserMapper users;
  @Autowired NameUsageMapper nameUsages;
  @Autowired IdSeqMapper idSeq;
  @Autowired IssueMapper issues;
  @Autowired ColMatchRunMapper runs;
  @Autowired ColMatchJobService service;
  @Autowired ObjectMapper json;

  @MockitoBean ClbMatchClient clb;

  private int createUser(String username) {
    AppUser u = new AppUser();
    u.setUsername(username);
    users.insert(u);
    return u.getId();
  }

  private int createProject(String title) {
    Project p = new Project();
    p.setTitle(title);
    p.setNomCode(NomCode.ZOOLOGICAL);
    projects.insert(p);
    return p.getId();
  }

  private NameUsage createUsage(int projectId, String scientificName, List<String> alternativeId, int userId) {
    NameUsage u = new NameUsage();
    u.setProjectId(projectId);
    u.setId(idSeq.allocate(projectId, ENTITY));
    u.setStatus(Status.ACCEPTED);
    u.setScientificName(scientificName);
    u.setRank("species");
    u.setAlternativeId(alternativeId);
    u.setModifiedBy(userId);
    nameUsages.insert(u);
    return u;
  }

  // Minimal CLB /match/nameusage response shape: only the two fields bestColId reads
  // (type, usage.id) -- mirrors ColMatchIT's more elaborate fixtures, trimmed to what matchOne uses.
  private tools.jackson.databind.JsonNode matched(String colId) {
    return json.readTree("{\"type\":\"EXACT\",\"usage\":{\"id\":\"" + colId + "\"}}");
  }

  private tools.jackson.databind.JsonNode noMatch() {
    return json.readTree("{\"type\":\"NONE\",\"usage\":null}");
  }

  private List<Issue> colIssues(int projectId, int usageId) {
    return issues.findByEntity(projectId, ENTITY, usageId).stream()
        .filter(i -> i.getRule().startsWith("col_"))
        .toList();
  }

  @Test
  void matchOneReconcilesAllFourOutcomes() {
    int userId = createUser("col-match-job-owner");
    int pid = createProject("col-match-job");

    NameUsage a = createUsage(pid, "Panthera leo", List.of("col:OLD"), userId);
    NameUsage b = createUsage(pid, "Panthera onca", null, userId);
    NameUsage c = createUsage(pid, "Panthera tigris", List.of("col:KEEP"), userId);
    NameUsage d = createUsage(pid, "Nonexistantus bogusii", null, userId);

    when(clb.match(eq("Panthera leo"), any(), any(), any(), anyList())).thenReturn(matched("NEW"));
    when(clb.match(eq("Panthera onca"), any(), any(), any(), anyList())).thenReturn(matched("B1"));
    when(clb.match(eq("Panthera tigris"), any(), any(), any(), anyList())).thenReturn(matched("KEEP"));
    when(clb.match(eq("Nonexistantus bogusii"), any(), any(), any(), anyList())).thenReturn(noMatch());

    // A: had col:OLD, COL now resolves to NEW -> UPDATED.
    assertThat(service.matchOne(pid, a.getId(), userId)).isEqualTo(ColOutcome.UPDATED);
    assertThat(nameUsages.findByIdInProject(pid, a.getId()).getAlternativeId()).containsExactly("col:NEW");
    List<Issue> aIssues = colIssues(pid, a.getId());
    assertThat(aIssues).hasSize(1);
    assertThat(aIssues.get(0).getRule()).isEqualTo("col_id_updated");
    assertThat(aIssues.get(0).getSeverity()).isEqualTo("INFO");

    // B: had no col id, COL resolves to B1 -> ADDED.
    assertThat(service.matchOne(pid, b.getId(), userId)).isEqualTo(ColOutcome.ADDED);
    assertThat(nameUsages.findByIdInProject(pid, b.getId()).getAlternativeId()).containsExactly("col:B1");
    List<Issue> bIssues = colIssues(pid, b.getId());
    assertThat(bIssues).hasSize(1);
    assertThat(bIssues.get(0).getRule()).isEqualTo("col_id_added");
    assertThat(bIssues.get(0).getSeverity()).isEqualTo("INFO");

    // C: had col:KEEP, COL confirms KEEP -> VERIFIED, nothing written, no flag.
    assertThat(service.matchOne(pid, c.getId(), userId)).isEqualTo(ColOutcome.VERIFIED);
    assertThat(nameUsages.findByIdInProject(pid, c.getId()).getAlternativeId()).containsExactly("col:KEEP");
    assertThat(colIssues(pid, c.getId())).isEmpty();

    // D: no COL match at all -> UNMATCHED, col_match_missing WARNING flag, alternativeId untouched.
    assertThat(service.matchOne(pid, d.getId(), userId)).isEqualTo(ColOutcome.UNMATCHED);
    assertThat(nameUsages.findByIdInProject(pid, d.getId()).getAlternativeId()).isNull();
    List<Issue> dIssues = colIssues(pid, d.getId());
    assertThat(dIssues).hasSize(1);
    assertThat(dIssues.get(0).getRule()).isEqualTo("col_match_missing");
    assertThat(dIssues.get(0).getSeverity()).isEqualTo("WARNING");

    // Idempotency: re-running matchOne for B against the SAME "B1" match now finds matched == stored
    // (B's alternativeId is already col:B1 from the run above), so the correct, stable outcome is
    // VERIFIED -- not a second ADDED -- and deleteColFlags' clear-then-(no re-insert) leaves B with
    // NO col_* flag, matching the same "VERIFIED -> no flag" contract exercised by C above. This is
    // the meaningful idempotency guarantee: re-matching an already-correct usage converges to a
    // stable, flag-free VERIFIED state rather than re-flagging or duplicating alternativeId entries.
    assertThat(service.matchOne(pid, b.getId(), userId)).isEqualTo(ColOutcome.VERIFIED);
    assertThat(nameUsages.findByIdInProject(pid, b.getId()).getAlternativeId()).containsExactly("col:B1");
    assertThat(colIssues(pid, b.getId())).isEmpty();
  }

  @Test
  void matchOneReturnsUnmatchedForMissingUsage() {
    int userId = createUser("col-match-job-missing-owner");
    int pid = createProject("col-match-job-missing");
    assertThat(service.matchOne(pid, 999999, userId)).isEqualTo(ColOutcome.UNMATCHED);
  }

  @Test
  void runSyncIteratesEveryUsageAndTalliesTheRun() {
    int userId = createUser("col-match-run-owner");
    int pid = createProject("col-match-run");

    NameUsage verified = createUsage(pid, "Ailuropoda melanoleuca", List.of("col:PANDA"), userId);
    NameUsage added = createUsage(pid, "Ursus arctos", null, userId);
    NameUsage unmatched = createUsage(pid, "Nonexistantus ursoides", null, userId);

    when(clb.match(eq("Ailuropoda melanoleuca"), any(), any(), any(), anyList())).thenReturn(matched("PANDA"));
    when(clb.match(eq("Ursus arctos"), any(), any(), any(), anyList())).thenReturn(matched("BEAR1"));
    when(clb.match(eq("Nonexistantus ursoides"), any(), any(), any(), anyList())).thenReturn(noMatch());

    ColMatchRun run = new ColMatchRun();
    run.setProjectId(pid);
    runs.insertRunning(run);
    assertThat(run.getId()).isNotNull();

    service.runSync(pid, run.getId(), userId);

    ColMatchRun after = runs.findById(run.getId());
    assertThat(after.getStatus()).isEqualTo("DONE");
    assertThat(after.getFinishedAt()).isNotNull();
    assertThat(after.getTotal()).isEqualTo(3);
    assertThat(after.getProcessed()).isEqualTo(3);
    assertThat(after.getVerified()).isEqualTo(1);
    assertThat(after.getAdded()).isEqualTo(1);
    assertThat(after.getUpdated()).isEqualTo(0);
    assertThat(after.getUnmatched()).isEqualTo(1);

    assertThat(nameUsages.findByIdInProject(pid, verified.getId()).getAlternativeId()).containsExactly("col:PANDA");
    assertThat(nameUsages.findByIdInProject(pid, added.getId()).getAlternativeId()).containsExactly("col:BEAR1");
    assertThat(nameUsages.findByIdInProject(pid, unmatched.getId()).getAlternativeId()).isNull();
  }
}
