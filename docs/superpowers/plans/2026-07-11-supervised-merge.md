# Supervised Project Merge — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Merge a staging (source) project into an existing target project under supervision — homonym-safe local matching of names **and** references, a dry-run plan + impact metrics, a reviewable/overridable mapping, and a global apply mode (overwrite / fill-gaps / new-only) that keeps matched target ids stable and slots new names at their nearest matched ancestor.

**Architecture:** Mirror the async-job pattern of `import_run`/`col_match_run` for a new `merge_run`. A shared matcher shape runs per entity type (name-usages, references) producing categorized candidates; a plan is computed (JSONB, nothing applied), reviewed/overridden, then applied. The **apply reuses the ColDP import's refs→names→children load logic** (`ImportRunService`) as its mechanical template — the merge-specific parts are matching, the id-remap to *existing* target ids, mode reconciliation, and classification insertion at a matched ancestor.

**Tech Stack:** Spring Boot 4.1 / Java 25 / Postgres 17 (pg_trgm) / MyBatis / Flyway (Jackson 3 `tools.jackson`); GBIF name-parser (via `NameParserService`); React 18 / Mantine 7 / mantine-react-table / Vitest + MSW.

## Global Constraints

- Build/test with **JDK 25 via `current`**: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw ...` (`current` → `25.0.3-librca`; default java 21 won't compile).
- **Run `clean verify` (not incremental) when a record's arity/component types change.**
- Commit directly to `main`; one commit per task after tests pass.
- Spec: `docs/superpowers/specs/2026-07-11-supervised-merge-design.md`. Next migration is **V19**.
- **Reuse, don't reinvent** (committed, read them): the async-job scaffold `coldp/imprt/{ImportRunService,ImportRunMapper,ImportRunRecovery,ImportAsyncConfig,dto/ImportRunResponse}` + `import_run` migration (V18) as the mirror for `merge_run`; the apply load logic in `ImportRunService` (id maps, `NameUsageMapper.insert`/`updateHierarchy`, `SynonymAcceptedMapper.link`, `TaxonInfoMapper.upsert`, `IdSeqMapper.allocate`, the 7 child mappers, `mergeScopedId`, `ValidationService.revalidateProject`); `NameParserService`; pg_trgm (indexes `name_usage_sciname_trgm`, `reference_citation_trgm` exist).
- **Package:** `org.catalogueoflife.editor.merge`.
- **Two entity types share one plan shape** — `record Candidate(String sourceId, Category category, String targetId, Double score)` and its override — implemented per entity, not forced into one generic class where it hurts readability.

## File structure

- `merge/MergeRun.java`, `MergeRunMapper.java`, `dto/MergeRunResponse.java`, `dto/MergePlan.java` (+ `Candidate`, `Category`, `Mode`), `MergeAsyncConfig.java`, `MergeRunRecovery.java` — job + plan model.
- `merge/NameMatcher.java`, `ReferenceMatcher.java` — the matchers (+ their canonical/normalization helpers).
- `merge/MergeService.java` — compute-plan async job + overrides; `MergeApplyService.java` — apply.
- `merge/MergeController.java` — endpoints.
- `db/migration/V19__merge_run.sql`.
- Frontend: `api/merge.ts`, `merge/MergeModal.tsx`, `merge/MergeMappingTables.tsx`; wire into `projects/ProjectMetadataPage.tsx` (or the Project page).

---

### Task 1: `merge_run` data layer + async/recovery infra

**Files:** Create `db/migration/V19__merge_run.sql`, `merge/MergeRun.java`, `MergeRunMapper.java`, `dto/MergeRunResponse.java`, `dto/MergePlan.java` (holds `Category`, `Mode`, `Candidate` + `List<Candidate> names/references`), `MergeAsyncConfig.java`, `MergeRunRecovery.java`. Test: `merge/MergeRunMapperIT.java`.

**Interfaces:**
- Produces: `merge_run` table; `MergeRun` POJO; `enum Category { MATCHED, POSSIBLE_HOMONYM, POSSIBLE_FUZZY, POSSIBLE, NEW }`; `enum Mode { OVERWRITE, FILL_GAPS, NEW_ONLY }`; `record Candidate(String sourceId, Category category, String targetId, Double score)`; `record MergePlan(List<Candidate> references, List<Candidate> names)`; `MergeRunMapper` (below); `MergeAsyncConfig.EXECUTOR_BEAN="mergeTaskExecutor"`.

- [ ] **Step 1: V19 migration** — mirror `V18__import_run.sql`:
```sql
CREATE TABLE merge_run (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id           INTEGER NOT NULL REFERENCES app_user(id),
  source_project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  target_project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  status            TEXT NOT NULL,          -- RUNNING | PLANNED | APPLYING | DONE | FAILED
  mode              TEXT,                   -- null until apply: OVERWRITE|FILL_GAPS|NEW_ONLY
  transactional     BOOLEAN,                -- null until apply
  plan              JSONB,                  -- {references:[Candidate], names:[Candidate]}
  metrics           JSONB,
  issues            JSONB,
  started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  planned_at        TIMESTAMPTZ,
  finished_at       TIMESTAMPTZ,
  error             TEXT
);
CREATE INDEX merge_run_target_idx ON merge_run (target_project_id, started_at DESC);
-- one active run per TARGET project (RUNNING plan-compute OR APPLYING)
CREATE UNIQUE INDEX merge_run_active_idx ON merge_run (target_project_id)
  WHERE status IN ('RUNNING','APPLYING');
```

- [ ] **Step 2: POJO + DTOs + enums** — `MergeRun` getters/setters (all columns; `plan`/`metrics`/`issues` held as raw JSON `String`). `MergePlan`/`Candidate`/`Category`/`Mode` as above. `MergeRunResponse` record (id, sourceProjectId, targetProjectId, status, mode, transactional, metrics parsed to a `MergeMetrics` record, issues parsed, timestamps, error) + `of(MergeRun, ObjectMapper)`. `MergeMetrics` record: `names{new,matched,possibleHomonym,possibleFuzzy}`, `references{new,matched,possible}`, `newAccepted`, `newSynonyms`, `unanchored` (ints).

- [ ] **Step 3: `MergeRunMapper`** (mirror `ImportRunMapper`): `insertRunning(MergeRun)` (@Options generated id, status RUNNING), `setPlanned(long runId, String plan, String metrics)` (status→PLANNED + planned_at=now()), `updatePlan(long runId, String plan)` (overrides save), `startApply(long runId, String mode, boolean transactional)` (status→APPLYING + mode + transactional), `finish(long runId, String issues)` (status→DONE + finished_at), `fail(long runId, String error)` (**`... WHERE id=#{runId} AND status <> 'DONE'`** — never clobber a DONE run, the import final-review lesson), `findById`, `findLatestByTarget(int targetProjectId)`, `findActiveByTarget(int targetProjectId)`, `failStaleRunning()` (RUNNING or APPLYING → FAILED). JSONB via `#{x,jdbcType=OTHER}::jsonb`.

- [ ] **Step 4: `MergeAsyncConfig`** (bean `mergeTaskExecutor` only, single-thread, queue 50 — do NOT re-declare `@EnableAsync`/`@EnableScheduling`) + `MergeRunRecovery` (`@EventListener(ApplicationReadyEvent)` → `failStaleRunning()`).

- [ ] **Step 5: Tests + clean verify + commit.** `MergeRunMapperIT`: insertRunning→findById; setPlanned stores plan+metrics+PLANNED; updatePlan; startApply sets mode+transactional+APPLYING; finish→DONE; fail does NOT flip a DONE run (guard); findLatestByTarget; findActiveByTarget; failStaleRunning flips RUNNING+APPLYING. Clean verify. Commit `feat(merge): merge_run data layer + async/recovery infra`.

---

### Task 2: `NameMatcher` — canonical key, author-compat, categories

**Files:** Create `merge/NameMatcher.java`. Test: `merge/NameMatcherTest.java` (unit) + `merge/NameMatcherIT.java` (DB, trigram).

**Interfaces:** Produces `NameMatcher.match(int sourceProjectId, int targetProjectId) : List<Candidate>` (one per source usage; `sourceId`/`targetId` are usage-id strings) + static `canonicalKey(NameUsage)`, `authorCompatible(String,String)`.

- [ ] **Step 1: Canonical key + author-compat (unit-first).**
```java
// author-stripped structural key: reconstruct from atomized fields (parseInto populated them),
// fall back to the raw scientificName for unparsed names; rank-qualified so a genus != a species
// of the same spelling.
static String canonicalKey(NameUsage u) {
  String core;
  if (notBlank(u.getUninomial())) core = u.getUninomial();
  else if (notBlank(u.getGenus())) core = String.join(" ",
      s(u.getGenus()), s(u.getInfragenericEpithet()), s(u.getSpecificEpithet()),
      s(u.getInfraspecificEpithet()), s(u.getCultivarEpithet()));
  else core = s(u.getScientificName());
  return norm(core) + "|" + norm(u.getRank());
}
private static String s(String x){ return x==null? "" : x.trim(); }
private static String norm(String x){ return x==null? "" : x.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+"," "); }

// heuristic: strip case + all non-alphanumerics; blank on either side = compatible. Conservative:
// mismatched author strings (e.g. "L." vs "(Linnaeus, 1758)") are NOT compatible -> POSSIBLE_HOMONYM
// (surfaced for review), never silently merged. Upgradeable to a GBIF AuthorComparator later.
static boolean authorCompatible(String a, String b) {
  String na = normAuthor(a), nb = normAuthor(b);
  return na.isEmpty() || nb.isEmpty() || na.equals(nb);
}
private static String normAuthor(String a){ return a==null? "" : a.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]",""); }
```
`NameMatcherTest`: two species same genus+epithet+rank → equal keys; a genus vs species same spelling → different keys (rank); unparsed name falls back to scientificName; author-compat: equal, blank-one-side true; "L." vs "(Linnaeus, 1758)" false; whitespace/case-insensitive.

- [ ] **Step 2: Matching (exact index + trigram fuzzy).** `match(sourceProjectId, targetProjectId)`:
  - Load `List<NameUsage> targets = usages.findAllByProject(targetProjectId)`; build `Map<String,List<NameUsage>> byKey` = group by `canonicalKey`.
  - For each `NameUsage src = ` in `usages.findAllByProject(sourceProjectId)`:
    - `List<NameUsage> cands = byKey.getOrDefault(canonicalKey(src), List.of())`.
    - If `cands` non-empty: partition by `authorCompatible(src.authorship, c.authorship)`.
      - exactly one author-compatible → `MATCHED` (targetId = its id, score 1.0).
      - ≥2 author-compatible → `POSSIBLE` (targetId = first, review — ambiguous).
      - none author-compatible → `POSSIBLE_HOMONYM` (targetId = first candidate as the suggestion).
    - else (no canonical candidate): fuzzy — `usages.findFuzzyCandidate(targetProjectId, src.getScientificName(), threshold)` (new mapper method, below). Non-null → `POSSIBLE_FUZZY` (targetId, score = similarity); null → `NEW`.
  - Return the list.
  - Add to `NameUsageMapper`: `@Select` `findFuzzyCandidate(targetProjectId, name, threshold)` → the single best target `(id, similarity)` via `WHERE project_id=#{targetProjectId} AND scientific_name % #{name} AND similarity(scientific_name,#{name}) >= #{threshold} ORDER BY similarity DESC LIMIT 1` (uses `name_usage_sciname_trgm`). Return a small `record ScoredId(int id, double score)` (or reuse). Threshold from config `coldp.merge.name-similarity:0.85`.

- [ ] **Step 3: IT + clean verify + commit.** `NameMatcherIT`: seed a target project (Panthera + Panthera leo (L.,1758)) and a source project with: an exact same-author name (→MATCHED), a same-canonical different-author name (→POSSIBLE_HOMONYM), a misspelling "Panthera leoo" (→POSSIBLE_FUZZY), a brand-new name (→NEW). Assert categories + targetIds. Clean verify. Commit `feat(merge): name matcher (canonical key, author-compat, trigram fuzzy)`.

---

### Task 3: `ReferenceMatcher` — DOI/citation normalization, categories

**Files:** Create `merge/ReferenceMatcher.java`. Test: `merge/ReferenceMatcherTest.java` + `ReferenceMatcherIT.java`.

**Interfaces:** Produces `ReferenceMatcher.match(int sourceProjectId, int targetProjectId) : List<Candidate>` (sourceId/targetId are reference-id strings) + static `normDoi(String)`, `normCitation(String)`.

- [ ] **Step 1: Normalizers (unit-first).**
```java
static String normDoi(String doi){
  if (doi==null || doi.isBlank()) return null;
  return doi.trim().toLowerCase(Locale.ROOT)
     .replaceFirst("^https?://(dx\\.)?doi\\.org/","").replaceFirst("^doi:","").trim();
}
static String normCitation(String c){
  if (c==null || c.isBlank()) return null;
  return c.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").replaceAll("[.,;]+$","");
}
```
`ReferenceMatcherTest`: DOI with/without `https://doi.org/` prefix normalize equal; citation whitespace/case/trailing-punct fold.

- [ ] **Step 2: Matching.** Load `List<Reference> targets = references.findAllByProject(targetProjectId)`; build `Map<String,Reference> byDoi` (non-null normDoi) and `Map<String,Reference> byCitation` (non-null normCitation, first-wins). For each source `Reference r`:
  - `normDoi(r)` in `byDoi` → `MATCHED`.
  - else `normCitation(r)` in `byCitation` → `MATCHED`.
  - else `references.findFuzzyCitation(targetProjectId, r.getCitation(), threshold)` (new `@Select`, trigram on `citation` via `reference_citation_trgm`, best `ScoredId`) non-null → `POSSIBLE` (score); null → `NEW`. Threshold `coldp.merge.citation-similarity:0.9`.

- [ ] **Step 3: IT + clean verify + commit.** `ReferenceMatcherIT`: target refs (one with DOI, one citation-only) + source refs (same DOI diff prefix →MATCHED, same citation →MATCHED, a fuzzy-similar citation →POSSIBLE, a new →NEW). Assert. Commit `feat(merge): reference matcher (DOI/citation normalization + trigram fuzzy)`.

---

### Task 4: `MergeService.computePlan` — plan job + metrics + endpoints + guards

**Files:** Create `merge/MergeService.java`, `MergeController.java`; modify nothing else. Test: `merge/MergePlanIT.java`.

**Interfaces:** Consumes `NameMatcher`, `ReferenceMatcher`, `MergeRunMapper`, `ProjectService` (role check). Produces `MergeService.start(userId, targetId, sourceId) : MergeRunResponse` (`@Async` computePlan), `get/latest`, `mapping(...)`. Endpoints below.

- [ ] **Step 1: `MergeService`** (mirror `ImportRunService` structure — `@Lazy self`, `@Async(MergeAsyncConfig.EXECUTOR_BEAN)`, `TaskRejectedException`→503):
  - `start(int userId, int targetId, int sourceId)`: `projectService.requireRole(userId, targetId)` must be owner/editor (a write); source must be a distinct project the user can read (`requireRole(userId, sourceId)`); reject `sourceId==targetId` (400). Pre-check `findActiveByTarget(targetId)` → 409; insert RUNNING; fire `self.computePlan(...)`; return `MergeRunResponse.of`.
  - `computePlan(long runId, int sourceId, int targetId)` `@Async`: `List<Candidate> refs = referenceMatcher.match(sourceId, targetId)`; `List<Candidate> names = nameMatcher.match(sourceId, targetId)`; build `MergeMetrics` (count categories; `newAccepted`/`newSynonyms` by looking up the source usage's status for NEW names — pass status through or re-read; `unanchored` = NEW accepted names whose source parent is not itself matched-or-new-with-a-resolvable-chain — compute in the plan or defer the unanchored count to apply-time and set 0 here with a note); `runs.setPlanned(runId, json(plan), json(metrics))`. On exception → `runs.fail`.
    - Keep `computePlan` NOT `@Transactional` (read-only; it writes only the merge_run row via the mapper).
- [ ] **Step 2: overrides + mapping reads** — `MergeService.getMapping(userId, targetId, runId, entity, category, page)` returns a paged slice of the stored plan's `names`/`references` filtered by category (parse `plan` JSON, filter, page in memory — the plan is one JSONB blob). `applyOverrides(userId, targetId, runId, overrides)` — Task 5.
- [ ] **Step 3: `MergeController`** (owner/editor on target):
  - `POST /api/projects/{targetId}/merge?source={sourceId}` → 202 `MergeRunResponse`.
  - `GET /api/projects/{targetId}/merge/{runId}` → 200 (summary + metrics; NOT the whole plan blob).
  - `GET /api/projects/{targetId}/merge/{runId}/mapping?entity=name|reference&category=&page=&size=` → paged `Candidate` rows (+ display fields: for names the source scientificName+authorship+rank and the target's; for refs the citations — join/enrich so the review table needs no extra fetch).
  - `GET /api/projects/{targetId}/merge/latest` → 200/204.
- [ ] **Step 4: IT + clean verify + commit.** `MergePlanIT`: seed source+target (reuse Task 2/3 fixtures), `POST .../merge?source=` → poll to PLANNED → assert metrics counts (names + refs) and that `GET .../mapping?entity=name&category=NEW` returns the expected rows with display fields. One-active guard: a second `start` while RUNNING/APPLYING → 409. Clean verify. Commit `feat(merge): compute-plan job + metrics + mapping endpoints + one-active guard`.

---

### Task 5: Overrides

**Files:** Modify `merge/MergeService.java`, `MergeController.java`; `dto/MergeOverride.java`. Test: `merge/MergeOverrideIT.java`.

**Interfaces:** Produces `PUT /api/projects/{targetId}/merge/{runId}/overrides` (body `List<MergeOverride>`), `record MergeOverride(String entity, String sourceId, Category category, String targetId)`.

- [ ] **Step 1:** `applyOverrides(userId, targetId, runId, List<MergeOverride>)` — only when status PLANNED (else 409). Load the plan JSON; for each override, find the `Candidate` in `names`/`references` by `(entity, sourceId)` and replace its `category` + `targetId` (confirm a POSSIBLE_* → MATCHED with a chosen `targetId`; reject a MATCHED → NEW with `targetId=null`; re-point → MATCHED + new `targetId`). Validate: a MATCHED override's `targetId` must exist in the target project (else a per-override 400/issue). Recompute metrics; `runs.updatePlan(runId, json(plan))` + re-store metrics. Return the updated `MergeRunResponse`.
- [ ] **Step 2: IT + clean verify + commit.** `MergeOverrideIT`: compute a plan, `PUT` an override rejecting an auto-MATCHED name (→NEW) and confirming a POSSIBLE_FUZZY (→MATCHED); GET run → metrics reflect it; overriding after status≠PLANNED → 409. Commit `feat(merge): plan overrides (confirm / reject / re-point)`.

---

### Task 6: Apply — references + name-usages (matched id-stable + new + classification)

**Files:** Create `merge/MergeApplyService.java`; modify `MergeController.java`. Test: `merge/MergeApplyNamesIT.java`.

**Interfaces:** Consumes the committed apply primitives (see Global Constraints) — this is the ColDP-import apply, generalized so a matched record maps to an **existing** target id. Produces `POST /api/projects/{targetId}/merge/{runId}/apply` (body `{mode, transactional}`), `MergeApplyService.apply(userId, targetId, runId, Mode, boolean transactional)`.

> **Mirror:** read `coldp/imprt/ImportRunService.java` — its references-then-names-then-children load, the `refIdMap`/`usageIdMap` id maps, `NameUsageMapper.insert`/`updateHierarchy` two-phase (self-FK), `SynonymAcceptedMapper.link`, `TaxonInfoMapper.upsert`, `mergeScopedId`/preserve-ids CURIE. The merge apply is the same shape with three differences: (1) a **matched** record's map entry is the **existing target id** (from the plan), not a freshly allocated one; (2) mode governs whether a matched record's scalars/relations change; (3) new accepted names attach at their nearest matched ancestor.

- [ ] **Step 1: Build id maps from the plan.** Load the plan. `refIdMap`: for each reference Candidate — MATCHED → `sourceId → targetId`; NEW → insert a new target reference (from the source reference; `idSeq.allocate(target,"reference")`) → `sourceId → newId`; MATCHED under OVERWRITE/FILL modes also reconcile the target ref's scalars (see mode rules, Task 7). Provenance `src:<sourceRefId>` CURIE on matched (default on). `usageIdMap`: MATCHED → `sourceUsageId → targetUsageId`; NEW → allocate a new target id → `sourceUsageId → newId` (insert deferred to Step 2).
- [ ] **Step 2: Apply name-usages (two-phase, like import).**
  - **NEW usages:** build the target `NameUsage` from the source (copy name/atomized/status fields; `publishedInReferenceId`/`referenceId[]` remap via `refIdMap`; `parseInto` to be safe; provenance `src:<sourceUsageId>` CURIE). Insert with hierarchy NULL (Pass 1), then Pass 2 `updateHierarchy` sets `parent_id`/`basionym_id` via `usageIdMap` (a NEW accepted name's `parent_id` = `usageIdMap.get(sourceParentId)` — which is the target id if the parent matched, or the new id if the parent is also new; **top-down insert order or the two-phase update both work since all ids are pre-allocated**). If the source parent maps to **nothing** (source parent unmatched AND not imported — shouldn't happen if the whole source is processed, but guard) → leave `parent_id` null (a new **root**) + a `merge_run.issue` "unanchored: <name>". Non-accepted NEW usages → `synonym_accepted.link` to `usageIdMap.get(sourceAcceptedId)` (not parent_id), exactly like import's status inverse.
  - **MATCHED usages:** the target id already exists — do NOT insert. Under NEW_ONLY: leave untouched. Under FILL_GAPS/OVERWRITE: reconcile per Task 7. Always: add the provenance `src:<sourceUsageId>` CURIE to the target usage's `alternative_id` (merge, dedup).
  - **Classification correctness:** a NEW accepted name under a MATCHED parent lands under the **target** parent (its `usageIdMap` entry is the target id) — this is "attach at nearest matched ancestor" for the direct-parent case; deeper new chains resolve transitively because every new ancestor is also in `usageIdMap`.
- [ ] **Step 3: Configurable transaction skeleton.** `apply(userId, targetId, runId, mode, transactional)` (non-`@Transactional`): `runs.startApply(runId, mode.name(), transactional)`. If `transactional` → `self.applyTransactional(...)` (a `@Transactional(rollbackFor=Exception.class)` method doing refs+names+children). If not → call the same steps **without** the wrapping transaction (each mapper insert auto-commits / batch via `TransactionTemplate` per entity-type — see Task 7). `POST .../apply {mode, transactional}` → 202; owner/editor; only when status PLANNED (else 409). On success `runs.finish`; on failure `runs.fail`.
- [ ] **Step 4: IT + clean verify + commit.** `MergeApplyNamesIT` (mode OVERWRITE, transactional true): source with a name MATCHED to a target usage (assert the target keeps its id + gains a `src:` CURIE), a NEW accepted name whose parent MATCHED (assert it's inserted under the target parent id), a NEW name whose parent is also NEW (assert the chain resolves), refs remapped. Clean verify. Commit `feat(merge): apply references + name-usages (matched id-stable, new + classification)`.

---

### Task 7: Apply — mode reconciliation, synonyms, child entities, validate, non-transactional/full-import

**Files:** Modify `merge/MergeApplyService.java`. Test: `merge/MergeApplyModesIT.java`, `MergeApplyFullImportIT.java`.

- [ ] **Step 1: Mode reconciliation of MATCHED records.** A small helper per entity applying `Mode`:
  - **OVERWRITE:** source scalars win — overwrite the target record's differing scalar fields from the source (names: authorship/rank/status/publishedIn/remarks/etc.; refs: citation/type/doi/etc.); then add missing relations.
  - **FILL_GAPS:** only fill **blank** target scalars from the source; add missing relations; never overwrite a non-blank target value.
  - **NEW_ONLY:** leave matched records entirely untouched (no scalar change, no relation add); only NEW records were added in Task 6.
  Implement as `reconcile(target, source, mode)` for name-usages (an `updateScalars` mapper call guarded by mode) and for references.
- [ ] **Step 2: Synonyms + child entities (mode-aware).**
  - **Synonyms:** a source synonym of a matched-or-new accepted → `synonym_accepted.link(target, synUsageId, acceptedTargetId, ordinal)` if not already present (dedup). Under NEW_ONLY, still add synonyms that are themselves NEW usages of matched accepteds? — **NEW_ONLY = only NEW records added**, so a NEW synonym usage IS added and linked; a MATCHED synonym is untouched. (Consistent: NEW_ONLY adds new records incl. new synonym usages, but never modifies matched records.)
  - **Child entities** (the 7): for a NEW usage, add all its children (remap `referenceID` via `refIdMap`, `taxonID`/`nameID` via `usageIdMap`), like import. For a MATCHED usage: FILL_GAPS → add children not already present (dedup by content); OVERWRITE → replace that usage's set of each present child type from the source; NEW_ONLY → skip. Respect the accepted-only guard for the 5 taxon-scoped children (mirror import's `acceptedUsageIds`).
- [ ] **Step 3: Non-transactional / full-import path.** When `transactional==false`: run refs, then names (Pass1+Pass2), then children, each phase committed via a `TransactionTemplate` in **batches** (e.g. per 500 records) rather than one big transaction — a mid-apply failure leaves a valid partial target + a `merge_run.issue` marking the failure point; the run still ends FAILED but is re-runnable (a fresh plan re-matches the applied records). **Full-import fast path:** if the plan has **no** MATCHED/POSSIBLE_* (all NEW — e.g. an empty target), default `transactional=false` at the controller and skip the reconcile/override machinery (straight adds). Document the batch size (`coldp.merge.apply-batch:500`).
- [ ] **Step 4: Post-commit validate.** After the apply (transactional commit or the last batch), `validationService.revalidateProject(targetId)` **best-effort** (its own try/catch that logs — the import final-review lesson: a post-commit validation throw must not flip a DONE run to FAILED). Then `runs.finish(runId, issuesJson)`.
- [ ] **Step 5: ITs + clean verify + commit.** `MergeApplyModesIT`: the SAME source+target fixture applied under each mode — OVERWRITE (matched target scalar changed to source value), FILL_GAPS (matched target non-blank kept, blank filled, synonym/child added), NEW_ONLY (matched target completely unchanged; only new added). Assert per mode. Also assert an override (a rejected auto-match → the name is added as NEW, not merged). `MergeApplyFullImportIT`: merge a source into an **empty** target with `transactional=false` → all NEW, full classification reproduced, refs added. Clean verify. Commit `feat(merge): apply modes + synonyms + child entities + validate + non-transactional/full-import`.

---

### Task 8: Frontend API + merge start/metrics/poll

**Files:** Create `frontend/src/api/merge.ts`. Test: covered via Task 9/10 MSW.

- [ ] **Step 1:** `api/merge.ts` — types `MergeRun`, `MergeMetrics`, `Candidate` (mirror the DTOs); `startMerge(targetId, sourceId)`, `getMergeRun(targetId, runId)`, `getLatestMerge(targetId)`, `getMergeMapping(targetId, runId, entity, category, page, size)`, `putMergeOverrides(targetId, runId, overrides)`, `applyMerge(targetId, runId, {mode, transactional})`. Reuse the `api()` client (200/204 handling like `getLatestColMatch`). Commit folded into Task 9.

---

### Task 9: Review UI — start, metrics, mode + apply, poll

**Files:** Create `frontend/src/merge/MergeModal.tsx`; modify `frontend/src/projects/ProjectMetadataPage.tsx`. Test: `frontend/src/merge/MergeModal.test.tsx`.

- [ ] **Step 1: `MergeModal`** — pick a **source project** (a Select of the user's OTHER projects via `listProjects`), start → poll (`refetchInterval` while RUNNING/APPLYING, mirror the col-match/export poll) → on PLANNED show the **impact metrics** panel (names: new/matched/possible-homonym/possible-fuzzy; references: new/matched/possible; new-accepted vs new-synonym; unanchored). A "Review mapping" button (Task 10). A **mode** SegmentedControl (overwrite / fill-gaps / new-only) + a **"Run in one transaction"** Switch (default on; **auto-off + a hint when the plan is large** — a record-count threshold, and forced off/hidden for a full-import all-NEW plan). **Apply** → poll to DONE → summary (applied counts + issues) + a link to the target project. Wire a **Merge** action on the Project page (owner/editor only) opening the modal.
- [ ] **Step 2: Test + gates + commit.** `MergeModal.test.tsx` (MSW): start → PLANNED metrics render; pick mode + apply → DONE summary + link; the transaction Switch defaults off for an all-NEW plan. `vitest` + `tsc` + build. Commit `feat(merge): review modal — metrics, mode + transaction, apply (frontend api + poll)`.

---

### Task 10: Review UI — mapping tables (name & reference tabs, override, re-point)

**Files:** Create `frontend/src/merge/MergeMappingTables.tsx`; modify `MergeModal.tsx`. Test: `frontend/src/merge/MergeMappingTables.test.tsx`.

- [ ] **Step 1: `MergeMappingTables`** — **two peer tabs of equal prominence, Names and References.** Each a `mantine-react-table` fed by `getMergeMapping` (paged, filter by category chips: MATCHED / POSSIBLE_HOMONYM / POSSIBLE_FUZZY / POSSIBLE / NEW). Each row shows the source record (name: scientificName+authorship+rank; ref: citation) and the matched target (if any) + score. Per-row **overrides**: confirm a POSSIBLE_* (→MATCHED), reject a MATCHED (→NEW), and **re-point** (a picker — a target-usage search for names, a target-reference search for refs). Batch the edits and `putMergeOverrides` on save; refetch metrics after.
- [ ] **Step 2: Test + gates + commit.** `MergeMappingTables.test.tsx` (MSW): the two tabs render with categorized rows; rejecting a MATCHED name posts an override and the metrics update; the reference tab shows ref rows. `vitest` + `tsc` + build. Commit `feat(merge): mapping review tables (name & reference tabs, override, re-point)`.

---

## Self-Review notes
- **Spec coverage:** merge_run + infra = T1; matchers (names, references first-class) = T2/T3; plan + metrics + mapping endpoints = T4; overrides = T5; apply (id-stable, modes, classification, provenance, configurable transaction, full-import) = T6/T7; review UI (metrics, mode+transaction, mapping tabs, override) = T9/T10. Reuse-for-follow-ons noted (matcher + apply are standalone services).
- **Type consistency:** `Candidate(sourceId,category,targetId,score)` and `enum Category`/`Mode` flow from T1 through matchers (T2/T3), plan (T4), overrides (T5), apply (T6/T7), and the frontend (T8-T10). `refIdMap`/`usageIdMap` (`Map<String,Integer>` source→target) mirror the import. `fail` guarded `WHERE status<>'DONE'` (T1) so a post-apply revalidation throw (T7) can't clobber DONE — the import lesson.
- **Reuse:** the apply (T6/T7) is explicitly the import's refs→names→children load with matched-id-remap + mode + classification-at-matched-ancestor; the two-phase self-FK insert and the accepted-only child guard carry over verbatim.
- **YAGNI / deferred:** cherry-pick (subtree scope), a chosen mount-point for unanchored branches (→ new roots for now), intra-project dedup and direct-CLB-import (reuse the matcher/apply, own specs) — all out of this plan, per the spec.
- **Large-data:** the configurable transaction (T6 skeleton, T7 batch path) + the full-import non-transactional default are the safety valve; the plan JSONB size limit is noted as a future streaming optimization.
