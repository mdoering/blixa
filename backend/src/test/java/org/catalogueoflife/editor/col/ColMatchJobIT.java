package org.catalogueoflife.editor.col;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.catalogueoflife.editor.name.ClbMatchClient;
import org.catalogueoflife.editor.name.IdSeqMapper;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.project.IdentifierScope;
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

// ColMatchJobService.matchOneScope/runSync: the synchronous, testable core of the project-wide
// bulk multi-scope match job. Entirely at the mapper/service level (like NameUsageMapperIT) -- no
// MockMvc/HTTP needed since ColMatchRunApiIT already covers the controller. The external CLB call
// (ClbMatchClient) is entirely mocked -- never a real network call. Every project seeded here
// carries THREE configured identifierScopes: col/3LXR and ipni/IPNI-42 (both matchable -- a
// non-blank datasetKey) plus a keyless tsn (present in the config, never matchable) -- proving both
// that distinct scopes reconcile independent <scope>:<id> CURIEs + <scope>_id_* flags on the SAME
// usage, and that a keyless scope is skipped entirely (never passed to ClbMatchClient.match, never
// written to alternativeId).
class ColMatchJobIT extends AbstractPostgresIT {

  private static final String ENTITY = "name_usage";
  private static final String COL_KEY = "3LXR";
  private static final String IPNI_KEY = "IPNI-42";

  private static final IdentifierScope COL = new IdentifierScope("col", COL_KEY);
  private static final IdentifierScope IPNI = new IdentifierScope("ipni", IPNI_KEY);
  private static final IdentifierScope TSN = new IdentifierScope("tsn", null);

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

  // Every project here carries the same three-scope config (col/3LXR + ipni/IPNI-42, both
  // matchable, + a keyless tsn) -- see the class javadoc. Written via ProjectMapper.updateMetadata
  // (the same JSONB typeHandler path ProjectService.updateMetadata uses), directly after insert.
  private int createProject(String title) {
    Project p = new Project();
    p.setTitle(title);
    p.setNomCode(NomCode.ZOOLOGICAL);
    projects.insert(p);
    p.setIdentifierScopes(List.of(COL, IPNI, TSN));
    projects.updateMetadata(p);
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
  // (type, usage.id) -- mirrors ColMatchIT's more elaborate fixtures, trimmed to what
  // matchOneScope uses.
  private tools.jackson.databind.JsonNode matched(String id) {
    return json.readTree("{\"type\":\"EXACT\",\"usage\":{\"id\":\"" + id + "\"}}");
  }

  private tools.jackson.databind.JsonNode noMatch() {
    return json.readTree("{\"type\":\"NONE\",\"usage\":null}");
  }

  // This scope's own flag(s) only (rule prefix "<scope>_id_") -- a usage matched in multiple
  // scopes carries multiple disjoint flag families side by side (col_id_*, ipni_id_*, ...), see
  // matchOneScopeAddsCoexistingScopedIdsAndFlags below.
  private List<Issue> scopeIssues(int projectId, int usageId, String scope) {
    return issues.findByEntity(projectId, ENTITY, usageId).stream()
        .filter(i -> i.getRule().startsWith(scope + "_id_"))
        .toList();
  }

  @Test
  void matchOneScopeReconcilesAllFourOutcomes() {
    int userId = createUser("col-match-job-owner");
    int pid = createProject("col-match-job");

    NameUsage a = createUsage(pid, "Panthera leo", List.of("col:OLD"), userId);
    NameUsage b = createUsage(pid, "Panthera onca", null, userId);
    NameUsage c = createUsage(pid, "Panthera tigris", List.of("col:KEEP"), userId);
    NameUsage d = createUsage(pid, "Nonexistantus bogusii", null, userId);

    when(clb.match(eq(COL_KEY), eq("Panthera leo"), any(), any(), any(), anyList())).thenReturn(matched("NEW"));
    when(clb.match(eq(COL_KEY), eq("Panthera onca"), any(), any(), any(), anyList())).thenReturn(matched("B1"));
    when(clb.match(eq(COL_KEY), eq("Panthera tigris"), any(), any(), any(), anyList())).thenReturn(matched("KEEP"));
    when(clb.match(eq(COL_KEY), eq("Nonexistantus bogusii"), any(), any(), any(), anyList())).thenReturn(noMatch());

    // A: had col:OLD, COL now resolves to NEW -> UPDATED.
    assertThat(service.matchOneScope(pid, a.getId(), COL, userId)).isEqualTo(ColOutcome.UPDATED);
    assertThat(nameUsages.findByIdInProject(pid, a.getId()).getAlternativeId()).containsExactly("col:NEW");
    List<Issue> aIssues = scopeIssues(pid, a.getId(), "col");
    assertThat(aIssues).hasSize(1);
    assertThat(aIssues.get(0).getRule()).isEqualTo("col_id_updated");
    assertThat(aIssues.get(0).getSeverity()).isEqualTo("INFO");

    // B: had no col id, COL resolves to B1 -> ADDED.
    assertThat(service.matchOneScope(pid, b.getId(), COL, userId)).isEqualTo(ColOutcome.ADDED);
    assertThat(nameUsages.findByIdInProject(pid, b.getId()).getAlternativeId()).containsExactly("col:B1");
    List<Issue> bIssues = scopeIssues(pid, b.getId(), "col");
    assertThat(bIssues).hasSize(1);
    assertThat(bIssues.get(0).getRule()).isEqualTo("col_id_added");
    assertThat(bIssues.get(0).getSeverity()).isEqualTo("INFO");

    // C: had col:KEEP, COL confirms KEEP -> VERIFIED, nothing written, no flag.
    assertThat(service.matchOneScope(pid, c.getId(), COL, userId)).isEqualTo(ColOutcome.VERIFIED);
    assertThat(nameUsages.findByIdInProject(pid, c.getId()).getAlternativeId()).containsExactly("col:KEEP");
    assertThat(scopeIssues(pid, c.getId(), "col")).isEmpty();

    // D: no COL match at all -> UNMATCHED, col_id_missing WARNING flag, alternativeId untouched.
    // (Renamed from the single-scope job's col_match_missing -- now uniformly "<scope>_id_missing".)
    assertThat(service.matchOneScope(pid, d.getId(), COL, userId)).isEqualTo(ColOutcome.UNMATCHED);
    assertThat(nameUsages.findByIdInProject(pid, d.getId()).getAlternativeId()).isNull();
    List<Issue> dIssues = scopeIssues(pid, d.getId(), "col");
    assertThat(dIssues).hasSize(1);
    assertThat(dIssues.get(0).getRule()).isEqualTo("col_id_missing");
    assertThat(dIssues.get(0).getSeverity()).isEqualTo("WARNING");

    // Idempotency: re-running matchOneScope for B against the SAME "B1" match now finds
    // matched == stored (B's alternativeId is already col:B1 from the run above), so the correct,
    // stable outcome is VERIFIED -- not a second ADDED -- and the reconcile's newRule==null deletes
    // B's col_id_added flag with no re-insert, leaving B with NO col_* flag, matching the same
    // "VERIFIED -> no flag" contract exercised by C above.
    assertThat(service.matchOneScope(pid, b.getId(), COL, userId)).isEqualTo(ColOutcome.VERIFIED);
    assertThat(nameUsages.findByIdInProject(pid, b.getId()).getAlternativeId()).containsExactly("col:B1");
    assertThat(scopeIssues(pid, b.getId(), "col")).isEmpty();
  }

  // The main win of the col_* reconcile refactor (see IssueMapper.findScopeFlags /
  // ColMatchJobService.reconcileScopeFlag): a curator's ACCEPTED review of a recurring
  // <scope>_id_missing flag must survive a bulk re-run -- an unconditional delete-then-insert would
  // reset it to OPEN every time, silently discarding "genuinely not in this scope" review
  // decisions. Exercised here on the ipni scope specifically (the col scope is exercised in
  // matchOneScopeReconcilesAllFourOutcomes above), proving the reconcile is per-scope, not just
  // per-usage.
  @Test
  void matchOneScopePreservesReviewStatusOnRecurringMissingFlag() {
    int userId = createUser("col-match-job-preserve-owner");
    int pid = createProject("col-match-job-preserve");

    NameUsage d = createUsage(pid, "Nonexistantus preservus", null, userId);
    when(clb.match(eq(IPNI_KEY), eq("Nonexistantus preservus"), any(), any(), any(), anyList())).thenReturn(noMatch());

    // First run: no ipni match -> a fresh OPEN ipni_id_missing flag.
    assertThat(service.matchOneScope(pid, d.getId(), IPNI, userId)).isEqualTo(ColOutcome.UNMATCHED);
    List<Issue> dIssues = scopeIssues(pid, d.getId(), "ipni");
    assertThat(dIssues).hasSize(1);
    Issue missing = dIssues.get(0);
    assertThat(missing.getRule()).isEqualTo("ipni_id_missing");
    assertThat(missing.getStatus()).isEqualTo("OPEN");

    // A curator reviews it: "genuinely not in IPNI" -> ACCEPTED.
    issues.review(missing.getId(), "ACCEPTED", userId);
    assertThat(scopeIssues(pid, d.getId(), "ipni").get(0).getStatus()).isEqualTo("ACCEPTED");

    // Re-run matchOneScope for D against ipni: ClbMatchClient is still stubbed to NONE, so
    // ipni_id_missing recurs unchanged -- it must be PRESERVED (same row, still ACCEPTED), not
    // reset to OPEN.
    assertThat(service.matchOneScope(pid, d.getId(), IPNI, userId)).isEqualTo(ColOutcome.UNMATCHED);
    List<Issue> afterRerun = scopeIssues(pid, d.getId(), "ipni");
    assertThat(afterRerun).hasSize(1);
    assertThat(afterRerun.get(0).getId()).isEqualTo(missing.getId());
    assertThat(afterRerun.get(0).getRule()).isEqualTo("ipni_id_missing");
    assertThat(afterRerun.get(0).getStatus()).isEqualTo("ACCEPTED");
  }

  // Rule-change case: a usage already flagged col_id_missing (from a prior run where CLB returned
  // NONE) whose NEXT run resolves to VERIFIED (matched now equals the usage's already-stored col
  // id) must have the stale col_id_missing flag deleted outright -- newRule==null reconciles to
  // "no flag" the same way a straight VERIFIED does.
  @Test
  void matchOneScopeDeletesMissingFlagWhenUsageLaterVerifies() {
    int userId = createUser("col-match-job-rulechange-owner");
    int pid = createProject("col-match-job-rulechange");

    NameUsage u = createUsage(pid, "Nonexistantus laterus", List.of("col:KEEP"), userId);

    when(clb.match(eq(COL_KEY), eq("Nonexistantus laterus"), any(), any(), any(), anyList())).thenReturn(noMatch());
    assertThat(service.matchOneScope(pid, u.getId(), COL, userId)).isEqualTo(ColOutcome.UNMATCHED);
    List<Issue> uIssues = scopeIssues(pid, u.getId(), "col");
    assertThat(uIssues).hasSize(1);
    assertThat(uIssues.get(0).getRule()).isEqualTo("col_id_missing");

    // CLB now confirms the already-stored col:KEEP -> VERIFIED -> the missing flag is deleted.
    when(clb.match(eq(COL_KEY), eq("Nonexistantus laterus"), any(), any(), any(), anyList())).thenReturn(matched("KEEP"));
    assertThat(service.matchOneScope(pid, u.getId(), COL, userId)).isEqualTo(ColOutcome.VERIFIED);
    assertThat(scopeIssues(pid, u.getId(), "col")).isEmpty();
  }

  @Test
  void matchOneScopeReturnsUnmatchedForMissingUsage() {
    int userId = createUser("col-match-job-missing-owner");
    int pid = createProject("col-match-job-missing");
    assertThat(service.matchOneScope(pid, 999999, COL, userId)).isEqualTo(ColOutcome.UNMATCHED);
  }

  // The core multi-scope proof: matching the SAME usage against col then ipni writes two
  // independent CURIEs (col:<id> + ipni:<id>, both coexisting on alternativeId) and two independent
  // flag families (col_id_added + ipni_id_added, both OPEN at once) -- mergeScopedId/findScopeFlags
  // never clobber each other across scopes.
  @Test
  void matchOneScopeAddsCoexistingScopedIdsAndFlags() {
    int userId = createUser("col-match-job-multiscope-owner");
    int pid = createProject("col-match-job-multiscope");

    NameUsage u = createUsage(pid, "Multiscopus testus", null, userId);

    when(clb.match(eq(COL_KEY), eq("Multiscopus testus"), any(), any(), any(), anyList())).thenReturn(matched("COLID1"));
    when(clb.match(eq(IPNI_KEY), eq("Multiscopus testus"), any(), any(), any(), anyList())).thenReturn(matched("IPNIID1"));

    assertThat(service.matchOneScope(pid, u.getId(), COL, userId)).isEqualTo(ColOutcome.ADDED);
    assertThat(service.matchOneScope(pid, u.getId(), IPNI, userId)).isEqualTo(ColOutcome.ADDED);

    NameUsage after = nameUsages.findByIdInProject(pid, u.getId());
    assertThat(after.getAlternativeId()).containsExactlyInAnyOrder("col:COLID1", "ipni:IPNIID1");
    assertThat(after.getAlternativeId()).noneMatch(s -> s.startsWith("tsn:"));

    List<Issue> colIssues = scopeIssues(pid, u.getId(), "col");
    assertThat(colIssues).hasSize(1);
    assertThat(colIssues.get(0).getRule()).isEqualTo("col_id_added");

    List<Issue> ipniIssues = scopeIssues(pid, u.getId(), "ipni");
    assertThat(ipniIssues).hasSize(1);
    assertThat(ipniIssues.get(0).getRule()).isEqualTo("ipni_id_added");
  }

  // A scope with no match (ipni here) flags <scope>_id_missing WITHOUT touching a different
  // scope's already-stored id (col:KEEP survives untouched).
  @Test
  void matchOneScopeFlagsMissingWithoutTouchingOtherScopes() {
    int userId = createUser("col-match-job-noipni-owner");
    int pid = createProject("col-match-job-noipni");

    NameUsage u = createUsage(pid, "Absentus ipnius", List.of("col:KEEP"), userId);

    when(clb.match(eq(IPNI_KEY), eq("Absentus ipnius"), any(), any(), any(), anyList())).thenReturn(noMatch());

    assertThat(service.matchOneScope(pid, u.getId(), IPNI, userId)).isEqualTo(ColOutcome.UNMATCHED);

    NameUsage after = nameUsages.findByIdInProject(pid, u.getId());
    assertThat(after.getAlternativeId()).containsExactly("col:KEEP");
    List<Issue> ipniIssues = scopeIssues(pid, u.getId(), "ipni");
    assertThat(ipniIssues).hasSize(1);
    assertThat(ipniIssues.get(0).getRule()).isEqualTo("ipni_id_missing");
    assertThat(ipniIssues.get(0).getSeverity()).isEqualTo("WARNING");
  }

  // runSync's headline contract: total/processed == usages x MATCHABLE scopes (2: col + ipni), the
  // keyless tsn scope contributing nothing at all -- ClbMatchClient.match is never even called with
  // a null datasetKey.
  @Test
  void runSyncIteratesUsagesTimesMatchableScopesAndSkipsKeylessScopes() {
    int userId = createUser("col-match-run-owner");
    int pid = createProject("col-match-run");

    NameUsage verified = createUsage(pid, "Ailuropoda melanoleuca", List.of("col:PANDA"), userId);
    NameUsage added = createUsage(pid, "Ursus arctos", null, userId);
    NameUsage unmatched = createUsage(pid, "Nonexistantus ursoides", null, userId);

    when(clb.match(eq(COL_KEY), eq("Ailuropoda melanoleuca"), any(), any(), any(), anyList())).thenReturn(matched("PANDA"));
    when(clb.match(eq(IPNI_KEY), eq("Ailuropoda melanoleuca"), any(), any(), any(), anyList())).thenReturn(matched("PANDA-IPNI"));
    when(clb.match(eq(COL_KEY), eq("Ursus arctos"), any(), any(), any(), anyList())).thenReturn(matched("BEAR1"));
    when(clb.match(eq(IPNI_KEY), eq("Ursus arctos"), any(), any(), any(), anyList())).thenReturn(noMatch());
    when(clb.match(eq(COL_KEY), eq("Nonexistantus ursoides"), any(), any(), any(), anyList())).thenReturn(noMatch());
    when(clb.match(eq(IPNI_KEY), eq("Nonexistantus ursoides"), any(), any(), any(), anyList())).thenReturn(noMatch());

    ColMatchRun run = new ColMatchRun();
    run.setProjectId(pid);
    runs.insertRunning(run);
    assertThat(run.getId()).isNotNull();

    service.runSync(pid, run.getId(), userId);

    ColMatchRun after = runs.findById(run.getId());
    assertThat(after.getStatus()).isEqualTo("DONE");
    assertThat(after.getFinishedAt()).isNotNull();
    // 3 usages x 2 matchable scopes (col, ipni) -- the keyless tsn scope contributes nothing.
    assertThat(after.getTotal()).isEqualTo(6);
    assertThat(after.getProcessed()).isEqualTo(6);
    // verified: PANDA/col (already stored). added: PANDA-IPNI/ipni + BEAR1/col. unmatched:
    // Ursus arctos/ipni + Nonexistantus ursoides/col + Nonexistantus ursoides/ipni.
    assertThat(after.getVerified()).isEqualTo(1);
    assertThat(after.getAdded()).isEqualTo(2);
    assertThat(after.getUpdated()).isEqualTo(0);
    assertThat(after.getUnmatched()).isEqualTo(3);

    // The keyless tsn scope is never even offered to ClbMatchClient.
    verify(clb, never()).match(isNull(), any(), any(), any(), any(), anyList());

    assertThat(nameUsages.findByIdInProject(pid, verified.getId()).getAlternativeId())
        .containsExactlyInAnyOrder("col:PANDA", "ipni:PANDA-IPNI");
    assertThat(nameUsages.findByIdInProject(pid, added.getId()).getAlternativeId())
        .containsExactly("col:BEAR1");
    assertThat(nameUsages.findByIdInProject(pid, unmatched.getId()).getAlternativeId()).isNull();

    assertThat(scopeIssues(pid, added.getId(), "ipni")).extracting(Issue::getRule)
        .containsExactly("ipni_id_missing");
    assertThat(scopeIssues(pid, unmatched.getId(), "col")).extracting(Issue::getRule)
        .containsExactly("col_id_missing");
    assertThat(scopeIssues(pid, unmatched.getId(), "ipni")).extracting(Issue::getRule)
        .containsExactly("ipni_id_missing");
  }
}
