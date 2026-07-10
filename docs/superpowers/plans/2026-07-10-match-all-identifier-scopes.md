# Bulk-match all configured identifier scopes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Configure identifier scopes with an optional CLB dataset key (JSONB), and generalize the bulk "Match all to COL" job to match every configured *matchable* scope, storing `<scope>:<id>` CURIEs with per-scope issue flags.

**Architecture:** Reuses the `col_match_run` async job, `ClbMatchClient`, `gbifOccurrenceLayer`/`identifierScopes` project-setting wiring, and the existing per-scope taxon-form fields. The main changes: a JSONB config shape, a parameterized match client, and a per-scope generalization of the reconcile + flags.

**Tech Stack:** Spring Boot 4.1 / Java 25 / Postgres 17 / MyBatis / Flyway (Jackson 3 `tools.jackson`); React 18 / Mantine 7 / Vitest.

## Global Constraints

- Build/test with **JDK 25 via `current`**: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw ...` (the `25.0.1-librca` path was removed; `current` → `25.0.3-librca`). Default java 21 won't compile.
- **Run `clean verify` when a record's arity/component types change** (incremental compilation false-greens on record changes).
- Commit directly to `main`; one commit per task after tests pass.
- Spec: `docs/superpowers/specs/2026-07-10-match-all-identifier-scopes-design.md`. Next migration is **V17**.
- Reuse (committed): `col/{ColMatchJobService,ColMatchRunMapper,ClbMatchClient,ColMatchService}`; `IssueMapper` col-flag helpers; the `gbifOccurrenceLayer`/`identifierScopes` project wiring; `frontend` Project settings page + the per-scope `TaxonDetail` fields (Task-3 of the taxon-form plan) + `ProjectMetadataPage` "Match all to COL" action.

---

### Task 1: `identifier_scopes` → JSONB `[{scope, datasetKey}]`

**Files:** Create `backend/.../db/migration/V17__identifier_scopes_jsonb.sql`, `backend/.../project/IdentifierScope.java` (record), a JSONB `TypeHandler`; modify `project/Project.java`, `ProjectMapper.java`, `dto/ProjectResponse.java`, `dto/UpdateProjectMetadataRequest.java`, `ProjectService.java`; `frontend/src/api/projects.ts`, the Project settings page, `frontend/src/tree/TaxonDetail.tsx`. Tests: `ProjectApiIT`; frontend.

**Interfaces:**
- Produces: `project.identifier_scopes JSONB`; `record IdentifierScope(String scope, String datasetKey)`; `Project.identifierScopes : List<IdentifierScope>`; `ProjectResponse.identifierScopes`/`UpdateProjectMetadataRequest.identifierScopes : List<IdentifierScope>`. A scope is **matchable** iff `datasetKey != null && !blank`.

- [ ] **Step 1: V17 migration** — `ALTER TABLE project ALTER COLUMN identifier_scopes TYPE jsonb USING (CASE WHEN identifier_scopes IS NULL THEN NULL ELSE (SELECT jsonb_agg(jsonb_build_object('scope', s)) FROM unnest(identifier_scopes) s) END);`
- [ ] **Step 2: JSONB TypeHandler** — `IdentifierScopeListTypeHandler extends BaseTypeHandler<List<IdentifierScope>>`: write via a `org.postgresql.util.PGobject` (type `"jsonb"`, value = Jackson `tools.jackson` `ObjectMapper.writeValueAsString(list)`); read via `objectMapper.readValue(rs.getString(col), new TypeReference<List<IdentifierScope>>(){})` (null → null). Get the shared `ObjectMapper` (a MyBatis TypeHandler can't easily @Autowire — use a static holder set from a `@Configuration`, OR construct a plain `tools.jackson.databind.ObjectMapper` in the handler; a plain mapper is fine for a simple record). `record IdentifierScope(String scope, String datasetKey)`.
- [ ] **Step 3: Wire the model/DTOs** — `Project.identifierScopes : List<IdentifierScope>` (was `List<String>`); `ProjectMapper` SELECT `@Result(property="identifierScopes", column="identifier_scopes", typeHandler=IdentifierScopeListTypeHandler.class)` + UPDATE `identifier_scopes = #{identifierScopes, typeHandler=...IdentifierScopeListTypeHandler, jdbcType=OTHER}`. `ProjectResponse`/`UpdateProjectMetadataRequest` component type `List<String>` → `List<IdentifierScope>`; `ProjectService.updateMetadata` null-safe carry-over unchanged. Update positional `new ProjectResponse(`/`new UpdateProjectMetadataRequest(` call sites (grep main+test).
- [ ] **Step 4: Frontend** — `Project.identifierScopes: {scope:string; datasetKey?:string|null}[]`. Project settings: replace the plain scope multi-select with an editable rows list (each: scope creatable-select from `/api/coldp/id-scopes` + custom, and a "Dataset key" `TextInput`; add/remove rows; a `col` scope defaults its datasetKey to `3LXR` with a hint). Include in the metadata update payload. `TaxonDetail`: change the per-scope field source from `project.identifierScopes` (strings) to `project.identifierScopes.map(s => s.scope)` — the field logic is otherwise unchanged.
- [ ] **Step 5: Tests + CLEAN verify + commit.** `ProjectApiIT`: update with `identifierScopes:[{scope:"col",datasetKey:"3LXR"},{scope:"ipni"}]` → GET → round-trips; the migration converts a pre-seeded TEXT[]-style scope (or assert default null). Frontend: the scope+datasetKey editor round-trips; the taxon-form per-scope fields still render (extend the Task-3 tests for the new shape). `clean verify` + `vitest` + `tsc --noEmit` + build. Commit `feat(project): identifier scopes carry a CLB dataset key (JSONB)`.

---

### Task 2: Parameterize `ClbMatchClient.match(datasetKey, …)`

**Files:** `backend/.../name/ClbMatchClient.java`, `ColMatchService.java`. Test: `ColMatchIT` (unchanged behavior).

**Interfaces:** Produces `ClbMatchClient.match(String datasetKey, String sciName, String authorship, String rank, String code, List<RankName> classification)` — the URL uses `datasetKey` instead of the baked-in config.

- [ ] **Step 1:** Add `String datasetKey` as the FIRST param of `ClbMatchClient.match`; use it in the `/dataset/{ds}/match/nameusage` URI instead of the injected `matchDataset` field. Keep the `matchDataset` config value (default `3LXR`) exposed via a getter or keep the field for the single-taxon default.
- [ ] **Step 2:** `ColMatchService.match` (single-taxon, for the modal) passes the config `3LXR` (the default COL dataset) to `clb.match(...)`. Behavior unchanged.
- [ ] **Step 3:** `ColMatchIT` still green (the mock adapts to the new signature). Clean verify. Commit `refactor(col): ClbMatchClient.match takes an explicit dataset key`.

---

### Task 3: Generalize the bulk job to all matchable scopes + per-scope flags

**Files:** `backend/.../col/ColMatchJobService.java`, `ColMatchRunMapper.java` (counters unchanged), `IssueMapper.java` (scope-parameterized col-flag helpers); `ProjectService`/`ProjectMapper` (read `identifierScopes` for the job). Test: `ColMatchJobIT`.

**Interfaces:** Produces `matchOneScope(projectId, usageId, IdentifierScope scope, userId) : ColOutcome`; `runSync` iterates usages × matchable scopes; per-scope flag rules `<scope>_id_added|_updated|_missing`.

- [ ] **Step 1: Per-scope flag helpers** — generalize `IssueMapper.insertColFlag`/`findColFlags` (from the col-match work): the rule keys are now `scope + "_id_" + outcome`. `findColFlags(projectId, entityType, entityId, scope)` → the 3 keys for that scope (`<scope>_id_added/_updated/_missing`); `deleteColFlags`→ delete those 3 (or fold into the reconcile). Keep the reconcile-preservation logic (keep a reviewed flag when the same rule recurs) per scope.
- [ ] **Step 2: `matchOneScope`** — copy `matchOne`'s 4-branch reconcile but parameterized by `scope`: read the stored `<scope>:` id via `ColMatchService.scopedIdFrom(alternativeId, scope)` (add a scope-parameterized variant of `colIdFrom`, case-insensitive), match via `clb.match(scope.datasetKey(), …)`, `bestColId`, reconcile `<scope>:<id>` into `alternative_id` (merge preserving other scopes — reuse/generalize `mergeColId` to `mergeScopedId(ids, scope, id)`), CAS-honored, flags `<scope>_id_*`. VERIFIED/ADDED/UPDATED/UNMATCHED.
- [ ] **Step 3: `runSync`** — load the project's `identifierScopes`, filter to matchable (datasetKey non-blank); `total = usageIds.size() * matchableScopes.size()`; for each usage, for each matchable scope, `self.matchOneScope(...)` (via the `@Lazy self` proxy — per-usage-per-scope transaction) and `tick`. No matchable scopes → finish immediately (total 0).
- [ ] **Step 4: IT** (`ColMatchJobIT`, `@MockitoBean ClbMatchClient`) — a project with `col`/`3LXR` + `ipni`/key + a keyless `tsn`; stub `clb.match` per dataset key; assert usages get `col:` + `ipni:` (NOT `tsn:`), `<scope>_id_added`/`_missing` flags recorded, counters aggregate (processed == usages×2), a re-run preserves a reviewed `ipni_id_missing`. Clean verify. Commit `feat(col): bulk match all configured matchable identifier scopes (per-scope flags)`.

---

### Task 4: Frontend "Match all identifiers" + no-matchable-scopes handling

**Files:** `frontend/src/projects/ProjectMetadataPage.tsx` (rename/generalize the col-match action), maybe `frontend/src/api/col.ts` (labels only). Test: the Project-page test.

**Interfaces:** The existing `startColMatch`/`getColMatchRun` endpoints are unchanged (the run is generalized server-side); only the UI copy + the enable condition change.

- [ ] **Step 1:** Rename the **"Match all to COL"** button/section to **"Match all identifiers"** (copy only — the run/poll/summary UI and endpoints are unchanged). Disable it (or show a hint "configure identifier scopes with a dataset key") when `project.identifierScopes` has no entry with a `datasetKey`.
- [ ] **Step 2:** Update the Project-page test for the new label + the disabled-when-no-matchable-scopes case (mock a project with/without a matchable scope). `vitest` + `tsc --noEmit` + build.
- [ ] **Step 3:** Full `clean verify` + frontend gates. Commit `feat(col): "Match all identifiers" Project action (all matchable scopes)`.

---

## Self-Review notes
- Spec coverage: config JSONB = T1; match client param = T2; job generalization + per-scope flags = T3; UI = T4.
- Reuse: T3 mirrors the committed `matchOne` (4-branch, CAS-honored, `@Lazy self` per-item transaction, reconcile-preservation) generalized by scope; T1 mirrors the `identifierScopes` wiring but with a JSONB TypeHandler instead of `StringArrayTypeHandler`.
- Type consistency: `IdentifierScope(scope, datasetKey)` flows Project → job (matchable filter) → `clb.match(datasetKey,…)`; `<scope>_id_*` flag keys are built the same way in insert/find/delete; `mergeScopedId`/`scopedIdFrom` generalize the existing `mergeColId`/`colIdFrom` (which stay for the COL-specific single-taxon path + map).
- The single-taxon COL modal + GBIF map stay COL-only (unchanged) — only the bulk job generalizes.
- Verify during T1: the JSONB TypeHandler's `ObjectMapper` source (a plain `tools.jackson` mapper in the handler is simplest); the `jdbcType=OTHER` + PGobject cast for writing jsonb.
