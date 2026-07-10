# ColDP Export — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Export a project to a ColDP `.zip` (combined `NameUsage.tsv` + child/reference/author files + `metadata.yaml`) as an async job producing a downloadable file.

**Architecture:** An async `export_run` job (mirroring the existing `col_match_run` job: single-thread `@Async` via a `@Lazy self` proxy, partial-unique single-active guard, startup stale-sweep) writes each entity through a `ColdpMapping` (our row → `ColdpTerm`-keyed row) into a temp folder via the Plan-1 `ColdpTsv`/`ColdpMetadata` primitives, then `ColdpZip.zipFolder` → a file under `coldp.export.dir`. Endpoints start/poll/download.

**Tech Stack:** Spring Boot 4.1 / Java 25 / Postgres 17 / MyBatis / Flyway; the Plan-1 ColDP io (`org.catalogueoflife.editor.coldp.io.{ColdpTsv,ColdpZip,ColdpMetadata}`, `life.catalogue.coldp.ColdpTerm`); React 18 / Mantine 7 / Vitest.

## Global Constraints

- Build/test ONLY with JDK 25: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/25.0.1-librca ./mvnw ...`. No Testcontainers `api.version` workaround.
- Commit directly to `main`; one commit per task after its tests pass.
- Spec: `docs/superpowers/specs/2026-07-10-coldp-export-import-design.md`. Plan-1 primitives are committed.
- **Templates to mirror (col_match_run, committed):** `backend/.../col/{ColMatchRun,ColMatchRunMapper,ColMatchRunController,ColMatchJobService,ColMatchAsyncConfig,ColMatchRunRecovery}.java`, `dto/ColMatchRunResponse.java`, migrations `V12`/`V13`. Reuse their patterns: `@Lazy self` proxy for `@Async`, `TaskRejectedException`→fail+503, partial unique index `WHERE status='RUNNING'`, startup recovery sweep, `latest`/`{runId}` endpoints with literal-path precedence.
- **Plan-1 facts:** `ColdpTsv.writeFile(Path dir, ColdpTerm fileTerm, Iterable<Map<ColdpTerm,String>> rows)` writes `{simpleName}.tsv` with the FULL canonical column set (empty where a row omits a key). `ColdpMetadata.write(Path dir, ColdpMetadataDto md)`. `ColdpZip.zipFolder(Path folder, Path targetZip)`. Column names/order come from `ColdpTerm.RESOURCES.get(fileTerm)`; set only the columns you have data for (others auto-empty). Read `~/code/col/backend/coldp/src/main/java/life/catalogue/coldp/ColdpTerm.java` for the exact enum constant names.
- Next migration after V14 is **V15**.

---

### Task 1: `export_run` job skeleton + endpoints + guards (minimal archive)

**Files:**
- Create: `backend/src/main/resources/db/migration/V15__export_run.sql`
- Create: `backend/.../coldp/export/ExportRun.java`, `ExportRunMapper.java`, `dto/ExportRunResponse.java`, `ExportAsyncConfig.java`, `ExportRunService.java`, `ExportRunController.java`, `ExportRunRecovery.java`, `ExportRetentionSweep.java`, `ColdpWriter.java`
- Modify: `backend/src/main/resources/application.yml` (coldp.export.*)
- Test: `backend/src/test/.../coldp/export/ExportRunApiIT.java`

**Interfaces:**
- Produces:
  - `export_run(id BIGINT PK, project_id BIGINT FK, status TEXT, file_path TEXT, file_name TEXT, file_size BIGINT, name_usages INT DEFAULT 0, references INT DEFAULT 0, started_at, finished_at, error TEXT)` + partial unique index `WHERE status='RUNNING'`. (Add per-entity count columns as needed, or a single `entities INT`.)
  - `POST /api/projects/{pid}/export` → 202 `ExportRunResponse`; `GET /api/projects/{pid}/export/{runId}`; `GET /api/projects/{pid}/export/{runId}/file` (streams the zip, `Content-Disposition: attachment`); `GET /api/projects/{pid}/export/latest`.
  - `ColdpWriter.write(int projectId, Path targetZip)` — the entity-writing entry point; in this task it writes ONLY `metadata.yaml` (from the project) into a temp dir + zips it. Later tasks add entities to this method.
  - Config: `coldp.export.dir` (default a temp/data subdir), `coldp.export.ttl` (default `P7D`).

- [ ] **Step 1: V15 migration** — the `export_run` table + `CREATE UNIQUE INDEX export_run_active_idx ON export_run (project_id) WHERE status='RUNNING';`.

- [ ] **Step 2: Write the failing IT** (`ExportRunApiIT`, mirror `ColMatchRunApiIT`): create a project; `POST …/export` → 202, capture runId; poll `GET …/export/{runId}` until DONE (bounded poll like `ColMatchRunApiIT.pollUntilTerminal`); `GET …/export/{runId}/file` → 200, an `application/zip` body; write the bytes to a temp file, `ColdpZip.extractToTemp` it, and assert `metadata.yaml` exists and `ColdpMetadata.read(dir).title()` equals the project title. Also: `GET …/export/latest` returns the run; a second `POST` while one is RUNNING (insert a RUNNING row directly to make it deterministic) → 409.

- [ ] **Step 3: Run — FAIL. Step 4: Implement** the run table/mapper/DTO, `ExportAsyncConfig` (single-thread, `EXECUTOR_BEAN`), `ExportRunService` (`start` = member read auth via `projects.requireRole` + single-active pre-check + `DuplicateKeyException`→409 + `TaskRejectedException`→fail+503; `@Async run` via `@Lazy self` → `ColdpWriter.write(pid, dir/{runId}.zip)` → update file_path/size + DONE, or FAILED+delete-partial-file on error; `latest`/`get`/`fileFor`), `ExportRunController`, `ExportRunRecovery` (`@EventListener(ApplicationReadyEvent)` → mark stale RUNNING FAILED), `ExportRetentionSweep` (`@Scheduled` — delete files + rows older than `coldp.export.ttl`; enable `@EnableScheduling` if not already). `ColdpWriter.write` for now: make a temp dir, `ColdpMetadata.write(dir, projectToMetadataDto(project))`, `ColdpZip.zipFolder(dir, targetZip)`, clean up the temp dir.

- [ ] **Step 5: Run — PASS. Full `mvn verify`. Commit** `feat(coldp): async export_run job + endpoints (metadata.yaml archive)`.

---

### Task 2: NameUsage.tsv (combined, pro-parte derived ids)

**Files:**
- Modify: `backend/.../name/NameUsageMapper.java` (add `findAllByProject`), `SynonymAcceptedMapper.java` (add `findAllLinks`)
- Create: `backend/.../coldp/export/NameUsageColdpWriter.java` (or a method set on `ColdpWriter`)
- Modify: `ColdpWriter.java` (write `NameUsage.tsv`)
- Test: `backend/src/test/.../coldp/export/NameUsageExportIT.java`

**Interfaces:**
- Consumes: `ColdpTsv.writeFile`, `ColdpTerm`.
- Produces:
  - `NameUsageMapper.findAllByProject(int projectId) : List<NameUsage>` — the full-row SELECT (the same projection `findByIdInProject` uses, incl. the `taxon_info` LEFT JOIN), no id filter, `ORDER BY id`.
  - `SynonymAcceptedMapper.findAllLinks(int projectId) : List<int[]>` OR `List<SynAccLink>` (a `record SynAccLink(int synonymId, int acceptedId)`) — every `(synonym_id, accepted_usage_id)` pair in the project, so the writer can group accepted links per synonym without N+1.

- [ ] **Step 1: Write the failing IT** (`NameUsageExportIT`): build a project — root (accepted, kingdom) → child (accepted, species "Aus bus"); a synonym "Xus bus" of the species; and a **pro-parte** synonym "Dus bus" linked to BOTH the species and the root (two `synonym_accepted` links; use the existing synonym-link service/endpoints or insert links). Export (drive `ColdpWriter.write` directly, or via the job), extract, read `NameUsage.tsv` via `ColdpReader.stream(ColdpTerm.NameUsage)`. Assert: the accepted rows have `status` accepted + correct `parentID` (classification); "Xus bus" has one row, `parentID` = the species id; "Dus bus" has TWO rows — the primary (`ID` = its own id, `parentID` = the lower accepted id) and the derived (`ID` = `"<dusId>-<otherAccId>"`, `parentID` = the other accepted), both with `scientificName`="Dus bus". Assert `code` column = the project nomCode and `alternativeID` round-trips a seeded CURIE.

- [ ] **Step 2: Run — FAIL. Step 3: Add the mapper queries** (`findAllByProject`, `findAllLinks`).

- [ ] **Step 4: Implement the NameUsage writer.** For each usage from `findAllByProject`, build a `Map<ColdpTerm,String>` for the columns we have (use the exact `ColdpTerm` constants — verify in `ColdpTerm.java`; e.g. `ID, parentID, basionymID, status, rank, scientificName, authorship, uninomial, genericName, infragenericEpithet, specificEpithet, infraspecificEpithet, cultivarEpithet, notho, combinationAuthorship, combinationExAuthorship, combinationAuthorshipYear, basionymAuthorship, basionymExAuthorship, basionymAuthorshipYear, sanctioningAuthor, nameStatus, nameReferenceID, publishedInYear, publishedInPage, publishedInPageLink, gender, etymology, namePhrase, referenceID, code, extinct, environment, temporalRangeStart, temporalRangeEnd, link, remarks, ordinal, alternativeID`). Multi-value fields (`environment`, `referenceID`, `alternativeID`) join with `,`. `code` = project nomCode (uppercased enum name, per ColDP). Then:
  - **Accepted** usage → one row: `parentID` = `parent_id`; include the `taxon_info` fields.
  - **Synonym/misapplied** usage → look up its accepted links (grouped from `findAllLinks`, sorted ascending by acceptedId): the FIRST accepted → primary row (`ID` = usage id, `parentID` = that accepted); EACH additional accepted → a row with `ID` = `usageId + "-" + acceptedId`, `parentID` = that accepted, same name fields (no taxon_info). No accepted links → one row, empty `parentID`.
  Collect all rows and `ColdpTsv.writeFile(dir, ColdpTerm.NameUsage, rows)`. Wire this into `ColdpWriter.write` (call it before zipping) and set the `name_usages` count on the run.

- [ ] **Step 5: Run — PASS. Commit** `feat(coldp): export NameUsage.tsv (combined, pro-parte derived ids)`.

---

### Task 3: Reference.tsv + Author.tsv

**Files:**
- Modify: `backend/.../name/ReferenceMapper.java` (add `findAllByProject` — unpaginated)
- Create: `backend/.../coldp/export/ReferenceColdpWriter.java` (+ author)
- Modify: `ColdpWriter.java`
- Test: `backend/src/test/.../coldp/export/ReferenceExportIT.java`

**Interfaces:**
- Produces: `ReferenceMapper.findAllByProject(int projectId) : List<Reference>` (all rows, `ORDER BY id`). `AuthorMapper.findByProject` already exists.

- [ ] **Step 1: Write the failing IT**: a project with 2 references (one with a DOI) and (if the app can create authors) an author → export → `Reference.tsv` read back via `ColdpReader.stream(ColdpTerm.Reference)` has both, with `ID`, `citation`, `doi`, `type`, `title`, `alternativeID` mapped; `Author.tsv` present only if authors exist.
- [ ] **Step 2: Run — FAIL. Step 3: Implement** the reference writer (`id→ID, citation, type, author, editor, title, containerTitle→container_title, issued, volume, issue, page, publisher, doi, isbn, issn, link, alternativeID`) + author writer (`id→ID, given, family, suffix, affiliation, alternativeID`, and abbreviation per its ColdpTerm) using `ColdpTsv.writeFile` with `ColdpTerm.Reference`/`ColdpTerm.Author`; wire into `ColdpWriter.write` (skip the Author file when there are no authors); set the `references` count.
- [ ] **Step 4: Run — PASS. Commit** `feat(coldp): export Reference.tsv + Author.tsv`.

---

### Task 4: Child-entity files (TypeMaterial / Distribution / Vernacular / Media / Estimate / NameRelation / TaxonProperty)

**Files:**
- Modify: the 7 child mappers in `backend/.../child/*Mapper.java` (add `findByProject`)
- Create: `backend/.../coldp/export/ChildColdpWriter.java`
- Modify: `ColdpWriter.java`
- Test: `backend/src/test/.../coldp/export/ChildExportIT.java`

**Interfaces:**
- Produces: `findByProject(int projectId) : List<...Response>` on each of the 7 child mappers (all rows for the project; the response DTOs already carry `usageId` + the entity fields). The writer maps each to its ColDP file.

- [ ] **Step 1: Write the failing IT**: an accepted usage with a distribution (`tdwg:AB`), a type material (with lat/lon), a vernacular, a media, an estimate, a property; and a name_relation to another usage. Export → each file present, read back via `ColdpReader`, `taxonID`/`nameID` = the usage id, `referenceID` resolves, `NameRelation.relatedNameID` = the related usage id.
- [ ] **Step 2: Run — FAIL. Step 3: Implement** `ChildColdpWriter` — per entity: `findByProject`, map each row to a `Map<ColdpTerm,String>` for the right file term (`ColdpTerm.TypeMaterial/Distribution/VernacularName/Media/SpeciesEstimate/NameRelation/TaxonProperty`), keying the FK columns (`taxonID`/`nameID` = `usageId`; `NameRelation` `nameID`=usageId, `relatedNameID`=relatedUsageId; `referenceID`=referenceId). Skip a file when its list is empty. Wire all 7 into `ColdpWriter.write`.
- [ ] **Step 4: Run — PASS. Commit** `feat(coldp): export child-entity ColDP files`.

---

### Task 5: Frontend Export action + round-trip-out IT

**Files:**
- Create: `frontend/src/api/export.ts`
- Modify: the Project page (`frontend/src/projects/ProjectMetadataPage.tsx` or wherever the col-match "Match all" button lives — mirror it)
- Test: `frontend/src/...` (Project-page test), `backend/src/test/.../coldp/export/ExportRoundTripIT.java`
- Modify: `docs`/ledger only as needed

**Interfaces:**
- Produces: `startExport(pid)`, `getExportRun(pid, runId)`, `getLatestExport(pid)`, and a download URL helper `exportFileUrl(pid, runId)`; a Project-page **Export ColDP** button → poll → **Download** link on DONE, latest-on-mount (mirror the col-match run UI patterns).

- [ ] **Step 1: Backend round-trip-out IT** (`ExportRoundTripIT`): seed a project resembling the Felidae sample (a few accepted taxa + a synonym + a reference + one distribution + one type material) via the existing services; run the export job; extract the zip; assert `metadata.yaml` + `NameUsage.tsv` (correct row count incl. any pro-parte expansion) + `Reference.tsv` + the child files, reading each back via `ColdpReader` and checking entity counts + a couple of resolved cross-references. Do NOT assert byte-identity on any free-text field with internal whitespace (the reader collapses whitespace runs — Plan-1 finding).
- [ ] **Step 2: Run — FAIL/PASS as appropriate. Step 3: Frontend** — `api/export.ts` + the Project-page action (button gated to members; poll with `refetchInterval` while RUNNING; Download link `GET …/export/{runId}/file`; load latest on mount, seed-once). Extend a Project-page test (MSW: POST→RUNNING, GET→DONE with a file link) asserting the Download link appears.
- [ ] **Step 4: Full gates** — `JAVA_HOME=… ./mvnw verify` + `cd frontend && npx vitest run && npm run build` → all green (report counts). Commit `feat(coldp): Export ColDP Project action + round-trip-out IT`.

---

## Self-Review notes
- Spec coverage: the export architecture (async run + file artifact + guards) = Task 1; the format mapping (NameUsage combined + pro-parte derived ids, Reference/Author, child files) = Tasks 2–4; the Project action + round-trip = Task 5.
- Reuse: Task 1 mirrors `col_match_run` (run table, `@Lazy self` async, single-active partial index, startup sweep, `TaskRejectedException`→503, latest/{runId} endpoints) — point the implementer at those files rather than re-deriving.
- Type consistency: `ColdpWriter.write(projectId, targetZip)` is extended by Tasks 2–4 (each adds entity files to the same method); `findAllByProject`/`findByProject`/`findAllLinks` return full rows/links; the NameUsage pro-parte id scheme (`"<synId>-<accId>"`) matches the spec.
- Plan-1 carry-forwards honored: round-trip IT asserts normalized (not byte-identical) free-text; the writer only sets populated columns (ColdpTsv fills the rest empty).
- Verify during Task 1: confirm `@EnableScheduling` is present (for the retention sweep) or add it; confirm `coldp.export.dir` default is writable in tests (use a `@TempDir`-like configured path or an app-temp subdir).
