# Bulk Project-Wide COL Match — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A user-triggered job that matches every name in a project against COL, verifies/updates/adds the `col:` id, and flags each outcome as an issue (`col_id_added` / `col_id_updated` / `col_match_missing`).

**Architecture:** Reuses `ClbMatchClient` + `NameUsageMapper.findClassification` + `NameUsageService.setIdentifiers`/`mergeColId` from the "COL Map + Single-Taxon Matching" plan. Runs on a dedicated single-thread `@Async` executor (sequential = polite to the CLB API). Progress is tracked in a new `col_match_run` row (the `task` entity is a user work-session, not a job tracker, so it isn't reused). Flags are stored as issues; a small reconcile change keeps them from being swept by per-usage revalidation.

**Tech Stack:** Spring Boot 4.1 / Java 25 / Postgres 17 / MyBatis / Flyway; React 18 / Mantine 7; Vitest + MSW.

## Global Constraints

- Build with **JDK 25**: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/25.0.1-librca ./mvnw ...`.
- Commit directly to `main`; one commit per task after tests pass.
- **Depends on** the "COL Map + Single-Taxon Matching" plan being merged (needs `ClbMatchClient`, `findClassification`, `setIdentifiers`, `mergeColId`, `ColMatchService`).
- Col flag rule keys (constants): `col_id_added` (INFO), `col_id_updated` (INFO), `col_match_missing` (WARN). Entity type = `name_usage`.

---

### Task 1: Keep non-rule flags out of per-usage reconciliation

**Files:**
- Modify: `backend/.../validation/ValidationService.java` (`revalidateUsage` stale loop)
- Test: `backend/src/test/.../validation/ValidationReconcileIT.java`

**Interfaces:**
- Produces: `revalidateUsage` no longer deletes/mutates issues whose `rule` is not a registered `ValidationRule.key()`. This is what lets `col_*` flags survive normal usage edits. Consumed by Task 2/3 (flags persist across edits).

- [ ] **Step 1: Write the failing regression IT**

In `ValidationReconcileIT`, add a test: create a usage; insert an `col_match_missing` issue directly via `IssueMapper` (entity_type `name_usage`, entity_id = usage, severity WARNING, status OPEN); call `validationService.revalidateUsage(pid, usageId)`; assert the `col_match_missing` issue still exists (`IssueMapper.findByEntity` still returns it). (Without the fix it is deleted as "stale".)

- [ ] **Step 2: Run — FAIL** (issue was swept).

- [ ] **Step 3: Scope the stale loop to rule keys**

In `ValidationService.revalidateUsage`, before the stale loop compute the registered keys and skip anything outside them:
```java
Set<String> ruleKeys = rules.stream().map(ValidationRule::key).collect(java.util.stream.Collectors.toSet());
...
for (Issue existingIssue : existing) {
  if (currentRuleKeys.contains(existingIssue.getRule())) continue;
  if (!ruleKeys.contains(existingIssue.getRule())) continue; // non-rule flags (e.g. col_*) are owned elsewhere
  // ... existing markDone / deleteById logic unchanged ...
}
```

- [ ] **Step 4: Run — PASS.** Confirm `ValidationReconcileIT`'s existing idempotency tests still pass (`-Dtest=ValidationReconcileIT`). Commit `fix(validation): reconcile only sweeps its own rule-keyed issues`.

---

### Task 2: Col-match run storage + job core (synchronous, testable)

**Files:**
- Create: `backend/src/main/resources/db/migration/V12__col_match_run.sql`
- Create: `backend/.../col/ColMatchRun.java`, `ColMatchRunMapper.java`, `dto/ColMatchRunResponse.java`
- Create: `backend/.../col/ColMatchJobService.java`
- Modify: `backend/.../validation/IssueMapper.java` (add `insertColFlag`, `deleteColFlags`)
- Test: `backend/src/test/.../col/ColMatchJobIT.java`

**Interfaces:**
- Produces:
  - table `col_match_run(id BIGINT PK, project_id BIGINT FK, status TEXT, total INT, processed INT, verified INT, added INT, updated INT, unmatched INT, started_at, finished_at, error TEXT)`.
  - `enum ColOutcome { VERIFIED, ADDED, UPDATED, UNMATCHED }`.
  - `ColMatchJobService.matchOne(int projectId, int usageId, int userId) : ColOutcome` — the per-usage core (no async), used by Task 3's loop and directly by the IT.
  - `ColMatchJobService.runSync(int projectId, long runId, int userId)` — iterate + count (used by the IT; Task 3 wraps it in `@Async`).
  - `IssueMapper.insertColFlag(projectId, entityType, entityId, rule, severity, message)`, `IssueMapper.deleteColFlags(projectId, entityType, entityId)` (deletes rule IN the three col keys).
- Consumed by: Task 3.

- [ ] **Step 1: V12 migration**

```sql
CREATE TABLE col_match_run (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id  BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  status      TEXT NOT NULL,          -- RUNNING | DONE | FAILED
  total       INTEGER NOT NULL DEFAULT 0,
  processed   INTEGER NOT NULL DEFAULT 0,
  verified    INTEGER NOT NULL DEFAULT 0,
  added       INTEGER NOT NULL DEFAULT 0,
  updated     INTEGER NOT NULL DEFAULT 0,
  unmatched   INTEGER NOT NULL DEFAULT 0,
  started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at TIMESTAMPTZ,
  error       TEXT
);
```

- [ ] **Step 2: Write the failing IT**

`ColMatchJobIT` — `@MockitoBean ClbMatchClient clb`. Build a project with three accepted usages: A already has `alternativeId=["col:OLD"]`; B has none; C has `["col:KEEP"]`. Stub `clb.match` per scientific name: A → best `usage.id="NEW"` (so UPDATED), B → `usage.id="B1"` (ADDED), C → `usage.id="KEEP"` (VERIFIED); a fourth usage D → `type=NONE` (UNMATCHED). Call `service.matchOne` for each (or `runSync`) and assert:
- A: outcome UPDATED, usage alternativeId now `["col:NEW"]`, a `col_id_updated` INFO issue exists.
- B: ADDED, `["col:B1"]`, `col_id_added` issue.
- C: VERIFIED, `["col:KEEP"]` unchanged, no col flag.
- D: UNMATCHED, `col_match_missing` WARN issue.
Then re-run `matchOne` for B (idempotency): still one `col_id_added` (prior col flags cleared then re-created), alternativeId unchanged.

- [ ] **Step 3: Run — FAIL.**

- [ ] **Step 4: IssueMapper flag helpers**

```java
@Insert("""
    INSERT INTO issue (project_id, entity_type, entity_id, rule, severity, message, status, created_at, updated_at)
    VALUES (#{projectId}, #{entityType}, #{entityId}, #{rule}, #{severity}, #{message}, 'OPEN', now(), now())
    """)
int insertColFlag(@Param("projectId") int projectId, @Param("entityType") String entityType,
    @Param("entityId") int entityId, @Param("rule") String rule,
    @Param("severity") String severity, @Param("message") String message);

@Delete("""
    DELETE FROM issue WHERE project_id=#{projectId} AND entity_type=#{entityType} AND entity_id=#{entityId}
      AND rule IN ('col_id_added','col_id_updated','col_match_missing')
    """)
int deleteColFlags(@Param("projectId") int projectId, @Param("entityType") String entityType,
    @Param("entityId") int entityId);
```
(Confirm the `issue` columns against V-migration for the issue table; adjust `created_at`/`updated_at` names if different.)

- [ ] **Step 5: matchOne core**

```java
@Transactional
public ColOutcome matchOne(int projectId, int usageId, int userId) {
  NameUsage u = usages.findByIdInProject(projectId, usageId);
  if (u == null) return ColOutcome.UNMATCHED;
  issues.deleteColFlags(projectId, "name_usage", usageId);          // idempotent
  String stored = ColMatchService.colIdFrom(u.getAlternativeId());  // bare id or null
  String code = /* project nomCode upper */;
  var cls = usages.findClassification(projectId, usageId);
  JsonNode root = clb.match(u.getScientificName(), u.getAuthorship(), lower(u.getRank()), code, cls);
  String matched = bestColId(root);                                  // usage.id when type!=NONE & present, else null
  if (matched == null) {
    issues.insertColFlag(projectId, "name_usage", usageId, "col_match_missing", "WARNING",
        "No COL match for " + u.getScientificName());
    return ColOutcome.UNMATCHED;
  }
  if (matched.equals(stored)) return ColOutcome.VERIFIED;
  // add or update the id (CAS on current version)
  var merged = NameUsageService.mergeColId(u.getAlternativeId(), matched);
  usages.updateAlternativeId(projectId, usageId, merged, userId, u.getVersion());
  if (stored == null) {
    issues.insertColFlag(projectId, "name_usage", usageId, "col_id_added", "INFO", "Added col:" + matched);
    return ColOutcome.ADDED;
  }
  issues.insertColFlag(projectId, "name_usage", usageId, "col_id_updated", "INFO",
      "COL id changed col:" + stored + " -> col:" + matched);
  return ColOutcome.UPDATED;
}
```
`bestColId` reads `root.path("usage").path("id")` when `type != NONE`. `ColMatchService.colIdFrom` (make it `public static`) strips the `col:` prefix case-insensitively. Audit: `matchOne` runs off-request in Task 3, so the `updateAlternativeId` path's `AuditService.record` records an ungrouped change (as the dev seeder does) — no extra work.

- [ ] **Step 6: runSync**

```java
public void runSync(int projectId, long runId, int userId) {
  List<Integer> ids = usages.findAllIds(projectId);   // add this mapper query (project_id ordered)
  runs.setTotal(runId, ids.size());
  for (int id : ids) {
    ColOutcome o;
    try { o = matchOne(projectId, id, userId); }
    catch (RuntimeException e) { o = ColOutcome.UNMATCHED; /* flag missing already cleared; record as unmatched */ }
    runs.tick(runId, o.name());   // processed++ and the matching counter++
  }
  runs.finish(runId);
}
```
`ColMatchRunMapper`: `insertRunning`, `setTotal`, `tick` (UPDATE processed=processed+1 + the per-outcome counter), `finish` (status DONE, finished_at now), `fail`, `findById`. `NameUsageMapper.findAllIds(projectId)`.

- [ ] **Step 7: Run — PASS. Commit** `feat(col): bulk match job core + col_match_run + flag helpers`.

---

### Task 3: Async wiring, endpoints, and the Project-page action

**Files:**
- Create: `backend/.../col/ColMatchAsyncConfig.java`
- Create: `backend/.../col/ColMatchRunController.java`
- Modify: `backend/.../col/ColMatchJobService.java` (`@Async run` + start)
- Modify: `frontend/src/api/col.ts` (new), the Project page
- Test: `backend/src/test/.../col/ColMatchRunApiIT.java`, `frontend` Project-page test

**Interfaces:**
- Produces:
  - `POST /api/projects/{pid}/col-match` (editor) → 202 `ColMatchRunResponse` (status RUNNING, id).
  - `GET /api/projects/{pid}/col-match/{runId}` → `ColMatchRunResponse` (poll for progress/summary).
  - `ColMatchAsyncConfig.EXECUTOR_BEAN` — single-thread pool (sequential CLB calls).

- [ ] **Step 1: Async config** — mirror `ValidationAsyncConfig`; one thread, small queue, bean name constant `EXECUTOR_BEAN`.

- [ ] **Step 2: Service start + async run**
```java
public ColMatchRunResponse start(int userId, int projectId) {
  requireEditor(userId, projectId);
  long runId = runs.insertRunning(projectId);
  self.run(projectId, runId, userId);   // self = injected proxy, so @Async applies
  return runs.findById(runId);
}
@Async(ColMatchAsyncConfig.EXECUTOR_BEAN)
public void run(int projectId, long runId, int userId) {
  try { runSync(projectId, runId, userId); }
  catch (Exception e) { runs.fail(runId, e.getMessage()); }
}
```

- [ ] **Step 3: Controller** — `POST` returns 202 + body; `GET /{runId}` returns the run (404 if not in project).

- [ ] **Step 4: Write the failing API IT** — `ColMatchRunApiIT` (`@MockitoBean ClbMatchClient` returning a fixed match): `POST …/col-match` → 202, capture runId; poll `GET …/col-match/{runId}` until `status=DONE` (bounded loop with the test's own retry, since @Async — or make the test executor synchronous via a test profile bean); assert counters (`processed==total`, and e.g. `added>=1`). Assert a `col_id_added`/`col_match_missing` issue appears in `GET …/issues`.

- [ ] **Step 5: Run — PASS** (add a test-profile synchronous executor if async timing is flaky, as validation ITs do).

- [ ] **Step 6: Frontend** — `api/col.ts`:
```ts
export interface ColMatchRun { id: number; status: string; total: number; processed: number; verified: number; added: number; updated: number; unmatched: number; }
export function startColMatch(pid: number) { return api<ColMatchRun>(`/api/projects/${pid}/col-match`, { method: 'POST' }); }
export function getColMatchRun(pid: number, runId: number) { return api<ColMatchRun>(`/api/projects/${pid}/col-match/${runId}`); }
```
Project page: a **Match all to COL** button (editors) → `startColMatch` → poll `getColMatchRun` (react-query `refetchInterval` while RUNNING) → show a progress bar + final summary (verified/added/updated/unmatched); note "flags appear in the Issues view". Invalidate `['issues',pid]` on completion.

- [ ] **Step 7: Frontend test** — MSW: POST returns RUNNING then GET returns DONE with counts; assert the summary renders and the Issues query is invalidated. `npx vitest run` + `npm run build` PASS.

- [ ] **Step 8: Full verify + commit** — `JAVA_HOME=… ./mvnw verify` (all ITs) PASS. Commit `feat(col): bulk Match-all-to-COL job endpoints + Project action`.

---

## Self-review notes
- Spec coverage: phase 7 table → Task 2 `matchOne` four branches (VERIFIED/ADDED/UPDATED/UNMATCHED) + flags; the reconcile-scoping constraint → Task 1; async + trigger + UI → Task 3.
- Deviation from spec wording: progress uses a dedicated `col_match_run` row + `@Async` executor rather than the user-facing `Task` entity (which is a change-grouping work-session, not a job tracker). Behaviour (async job, progress, summary, flags in Issues) matches the approved design.
- Type consistency: `ColMatchService.colIdFrom` (made `public static`) is the single bare-id parser reused by `matchOne` and the map endpoint; `mergeColId`/`updateAlternativeId` are the same write path as the single-taxon match. Col rule-key strings are identical in `insertColFlag`, `deleteColFlags`, and the frontend Issues filter.
- Verify during Task 2: the actual `issue` table column names (`created_at`/`updated_at`, `severity` casing) against the issue migration; align `insertColFlag`.
