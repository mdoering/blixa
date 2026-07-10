# ColDP Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Import a ColDP archive (`.zip` of TSV files + `metadata.yaml`) into a **new** project, as an async job — a generic, unsupervised load that round-trips our own exports and serves as a staging store for the later supervised merge.

**Architecture:** Mirror the existing `export_run` async infrastructure for a new `import_run`; **reverse** exactly what the export writers emit. Read archives with the CLB `life.catalogue.csv.ColdpReader` directly (streamed `ColdpTerm`-keyed `VerbatimRecord`s — the inverse of the export's `Map<ColdpTerm,String>` rows). Load in phases inside one transaction: metadata→project, references+authors, names/usages (two-pass id-remap + pro-parte re-merge + status inverse), child entities; then validate the project.

**Tech Stack:** Spring Boot 4.1 / Java 25 / Postgres 17 / MyBatis / Flyway (Jackson 3 `tools.jackson`); `org.catalogueoflife:reader:1.3.0-SNAPSHOT` (`ColdpReader`, `ColdpTerm`, `VerbatimRecord`, `Identifier.Scope`); React 18 / Mantine 7 / Vitest + MSW.

## Global Constraints

- Build/test with **JDK 25 via `current`**: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw ...` (`current` → `25.0.3-librca`; default java 21 won't compile).
- **Run `clean verify` (not incremental) when a record's arity/component types change** — Maven false-greens on record-constructor changes.
- Commit directly to `main`; one commit per task after tests pass.
- Spec: `docs/superpowers/specs/2026-07-10-coldp-export-import-design.md` (Import architecture section is authoritative). Next migration is **V18**.
- **The export writers are the authoritative mirror.** The import maps every `ColdpTerm` column back to the same record field the corresponding writer read from. Read, per entity, and invert:
  `coldp/export/NameUsageColdpWriter.java`, `ReferenceColdpWriter.java`, `AuthorColdpWriter.java`, `ChildColdpWriter.java`. Do NOT re-derive column names from the README — the writer + `ColdpTerm` are the source of truth.
- **Import is unsupervised and bypasses the per-row service layer.** Insert via the raw mappers (`NameUsageMapper.insert`, `ReferenceMapper.insert`, `AuthorMapper.insert`, the 7 child mappers' `insert`), exactly as the export writers *read* via raw mappers — NOT via `NameUsageService.create`/`ReferenceService.create`/`AbstractChildEntityService.create` (those enforce per-row auth/audit/validation-event and are far too slow for a bulk load). Validate once at the end via `ValidationService.revalidateProject`.
- **Id allocation:** `idSeq.allocate(projectId, entity)` where `entity` is `"name_usage"`, `"reference"`, `"author"`, or the child service's entity string. There is no DB sequence on these tables — the app allocates every id.
- **Reverse the writer's vocab transforms exactly.** Export writes enums via `lower(e) = e.name().toLowerCase(Locale.ROOT).replace('_',' ')` and status via `coldpStatus` (`ACCEPTED→"accepted"`, `SYNONYM→"synonym"`, `MISAPPLIED→"misapplied"`, `UNASSESSED→"provisionally accepted"`). Import must invert: upper-case + `replace(' ','_')` before `Enum.valueOf`, and map the ColDP status string back (`"provisionally accepted"→UNASSESSED`), case-insensitively. Array columns (`alternativeID`, `referenceID`, `environment`) are **comma**-joined.
- **Known export losses (import must NOT expect them):** `name_usage.sanctioning_author` and TypeMaterial `occurrence_id` have no ColDP column (sanctioningAuthor is re-derived by `NameParserService.parseInto`; occurrenceId is re-derivable by re-running the GBIF match). `name_usage.link` was dropped (V16) and is not written. Reference DOES carry `accessed` (V16). Whitespace: `ColdpReader` normalizes on read — round-trip ITs assert the normalized form for any multi-whitespace field.

---

### Task 1: `import_run` data layer + async/recovery infra + reverse-vocab helpers

**Files:**
- Create: `backend/src/main/resources/db/migration/V18__import_run.sql`
- Create: `backend/.../coldp/imprt/ImportRun.java`, `ImportRunMapper.java`, `dto/ImportRunResponse.java`, `ImportAsyncConfig.java`, `ImportRunRecovery.java`
- Create: `backend/.../coldp/imprt/ColdpParse.java` (reverse-vocab helpers)
- Test: `backend/.../coldp/imprt/ImportRunMapperIT.java`, `ColdpParseTest.java`

> Use package `org.catalogueoflife.editor.coldp.imprt` (`import` is a Java keyword — do not name the package `import`).

**Interfaces:**
- Produces: `import_run` table; `ImportRun` POJO; `ImportRunMapper` (signatures below); `ImportRunResponse` DTO; `ImportAsyncConfig.EXECUTOR_BEAN = "importTaskExecutor"`; `ColdpParse` static helpers: `Status parseStatus(String)`, `<E extends Enum<E>> E parseEnum(Class<E>, String)`, `List<String> csv(String)`, `List<Integer> csvInts(String)`, `Integer intOrNull(String)`.

- [ ] **Step 1: V18 migration** — mirror `V15__export_run.sql`, adapted for import (project is created by the job, so `project_id` is nullable; add import-specific columns):

```sql
-- import_run mirrors export_run (V15): a single async job row per import, RUNNING -> DONE|FAILED,
-- swept stale on startup. Unlike export, import CREATES the project, so project_id is nullable and
-- set once the job has created it. issues is a JSONB array of non-fatal per-row problems.
CREATE TABLE import_run (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id           INTEGER NOT NULL REFERENCES app_user(id),
  project_id        BIGINT REFERENCES project(id) ON DELETE CASCADE,
  status            TEXT NOT NULL,               -- RUNNING | DONE | FAILED
  source_name       TEXT,                        -- uploaded filename
  preserve_ids      BOOLEAN NOT NULL DEFAULT false,
  id_scope          TEXT,                        -- scope prefix for preserved source ids (iff preserve_ids)
  name_usage_count  INTEGER NOT NULL DEFAULT 0,
  reference_count   INTEGER NOT NULL DEFAULT 0,
  author_count      INTEGER NOT NULL DEFAULT 0,
  issues            JSONB,                       -- array of {entity, sourceId, message}
  started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at       TIMESTAMPTZ,
  error             TEXT
);
CREATE INDEX import_run_user_idx ON import_run (user_id, started_at DESC);
```
No partial-unique active index (import has no existing project to guard on; the single-thread executor serializes runs — note this in a SQL comment).

- [ ] **Step 2: `ImportRun` POJO** — plain getters/setters: `Long id; Integer userId; Long projectId; String status; String sourceName; Boolean preserveIds; String idScope; Integer nameUsageCount; Integer referenceCount; Integer authorCount; String issues; OffsetDateTime startedAt; OffsetDateTime finishedAt; String error;` (`issues` held as the raw JSON string; a JSONB TypeHandler is unnecessary — build the JSON once at finish, see Task 5).

- [ ] **Step 3: `ImportRunMapper`** (annotation mapper, mirror `ExportRunMapper`):
```java
void insertRunning(ImportRun run);   // @Options(useGeneratedKeys=true, keyProperty="id"); inserts (user_id, source_name, preserve_ids, id_scope, status='RUNNING')
int setProject(@Param("runId") long runId, @Param("projectId") long projectId);
int finish(@Param("runId") long runId, @Param("nameUsageCount") int nameUsageCount,
    @Param("referenceCount") int referenceCount, @Param("authorCount") int authorCount,
    @Param("issues") String issues);   // issues bound with jdbcType=OTHER, ::jsonb cast in SQL
int fail(@Param("runId") long runId, @Param("error") String error);
ImportRun findById(@Param("runId") long runId);
ImportRun findLatestByUser(@Param("userId") int userId);
int failStaleRunning();               // bulk UPDATE ... SET status='FAILED', error='interrupted by restart' WHERE status='RUNNING'
```
For `finish`, write `issues = #{issues,jdbcType=OTHER}::jsonb` (a JSON string or null).

- [ ] **Step 4: `ImportRunResponse`** — record mirroring `ExportRunResponse` (omit nothing sensitive; there is no file path). Fields: `Long id, Long projectId, String status, String sourceName, Boolean preserveIds, String idScope, Integer nameUsageCount, Integer referenceCount, Integer authorCount, List<ImportIssue> issues, OffsetDateTime startedAt, OffsetDateTime finishedAt, String error`. Add `record ImportIssue(String entity, String sourceId, String message)`. `static of(ImportRun, ObjectMapper)` parses the stored `issues` JSON (null → `List.of()`).

- [ ] **Step 5: `ImportAsyncConfig`** — mirror `ExportAsyncConfig` but **do NOT** add `@EnableAsync`/`@EnableScheduling` again (they are already declared on `ExportAsyncConfig`, application-wide). Only declare the executor bean:
```java
@Configuration
public class ImportAsyncConfig {
  public static final String EXECUTOR_BEAN = "importTaskExecutor";
  @Bean(EXECUTOR_BEAN)
  public Executor importTaskExecutor() {
    ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
    e.setCorePoolSize(1); e.setMaxPoolSize(1); e.setQueueCapacity(50);
    e.setThreadNamePrefix("coldp-import-"); e.initialize(); return e;
  }
}
```

- [ ] **Step 6: `ImportRunRecovery`** — copy `ExportRunRecovery`: `@EventListener(ApplicationReadyEvent.class)` → `runs.failStaleRunning()` + log the count.

- [ ] **Step 7: `ColdpParse`** — the exact inverse of the writer's vocab transforms:
```java
public final class ColdpParse {
  private ColdpParse() {}
  private static final Splitter CSV = Splitter.on(',').trimResults().omitEmptyStrings();

  public static Status parseStatus(String s) {          // inverse of NameUsageColdpWriter.coldpStatus
    if (s == null || s.isBlank()) return null;
    return switch (s.trim().toLowerCase(Locale.ROOT)) {
      case "accepted" -> Status.ACCEPTED;
      case "synonym", "ambiguous synonym" -> Status.SYNONYM;
      case "misapplied" -> Status.MISAPPLIED;
      case "provisionally accepted", "unassessed" -> Status.UNASSESSED;
      default -> Status.UNASSESSED;                     // unknown -> UNASSESSED (safest non-accepted)
    };
  }
  public static <E extends Enum<E>> E parseEnum(Class<E> type, String s) {  // inverse of lower(e)
    if (s == null || s.isBlank()) return null;
    try { return Enum.valueOf(type, s.trim().toUpperCase(Locale.ROOT).replace(' ', '_')); }
    catch (IllegalArgumentException e) { return null; }  // unknown vocab -> null (dropped), not fatal
  }
  public static List<String> csv(String s) { return s == null ? List.of() : CSV.splitToList(s); }
  public static List<Integer> csvInts(String s) {
    List<Integer> out = new ArrayList<>();
    for (String p : csv(s)) { try { out.add(Integer.valueOf(p)); } catch (NumberFormatException ignore) {} }
    return out;
  }
  public static Integer intOrNull(String s) {
    if (s == null || s.isBlank()) return null;
    try { return Integer.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
  }
}
```

- [ ] **Step 8: Tests + clean verify + commit.**
  - `ColdpParseTest` (unit, no Spring): `parseStatus("provisionally accepted")==UNASSESSED`, `"accepted"==ACCEPTED`, `"SYNONYM"==SYNONYM` (case-insensitive), `null`→null, `"weird"`→UNASSESSED; `parseEnum(NomCode.class,"zoological")==ZOOLOGICAL`, `parseEnum(Environment.class,"fresh water")` round-trips whatever `lower()` produces for a multi-word value (pick a real multi-word enum constant; if none, assert underscore round-trip on a fabricated single enum), `parseEnum(X,"bogus")==null`; `csv("a, b ,,c")==[a,b,c]`, `csvInts("1,2,x,3")==[1,2,3]`, `intOrNull("")==null`.
  - `ImportRunMapperIT` (Spring IT, mirror an existing `*MapperIT`/`ExportRunApiIT` setup): `insertRunning` → `findById`; `setProject` then `findById` shows projectId; `finish` sets counts + status DONE + issues JSON stored/retrievable; `fail` sets FAILED + error; `findLatestByUser` returns most-recent; `failStaleRunning` flips a RUNNING row to FAILED.
  - `JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw clean verify` → BUILD SUCCESS (report counts).
  - Commit `feat(coldp): import_run data layer + async/recovery infra + reverse-vocab helpers`.

---

### Task 2: Archive open + project creation (the async job skeleton) + endpoints

**Files:**
- Modify: `backend/.../coldp/io/ColdpZip.java` (add a size/entry cap to `extractToTemp`)
- Create: `backend/.../coldp/imprt/ImportRunService.java`, `ImportRunController.java`
- Config: `backend/src/main/resources/application*.yml` (`coldp.import.max-bytes`)
- Test: `backend/.../coldp/imprt/ImportApiIT.java`

**Interfaces:**
- Consumes: `ImportRunMapper`, `ColdpParse`, `ProjectService.create` / `updateMetadata`, `ColdpMetadata.read`, `ColdpZip.extractToTemp`, `life.catalogue.csv.ColdpReader.from(Path)`, `life.catalogue.coldp.ColdpTerm`.
- Produces: `ImportRunService.start(int userId, MultipartFile file, boolean preserveIds, String idScope) : ImportRunResponse`; `run(long runId, Path extractedDir, ...)` `@Async`; `get(userId, runId)`, `latest(userId)`. `POST /api/projects/import` (202), `GET /api/projects/import/{runId}`, `GET /api/projects/import/latest`.

- [ ] **Step 1: Size cap on extract** — add a total-bytes + entry-count cap to `ColdpZip.extractToTemp` (guard against zip bombs — uploaded archives are untrusted). Read `coldp.import.max-bytes` (default e.g. `104857600` = 100 MB) as a param; sum decompressed bytes across entries and throw `IOException("archive exceeds N bytes")` if exceeded; cap entry count (e.g. 10_000). Keep the existing zip-slip guard. Add a `ColdpZipTest` case asserting an over-cap archive throws.

- [ ] **Step 2: `ImportRunService`** — mirror `ExportRunService` structure (`@Lazy self`, single-thread async, `TaskRejectedException`→503):
  - `start(userId, file, preserveIds, idScope)`:
    - Validate: `file` non-empty; `preserveIds` implies non-blank `idScope` (else 400); `file.getSize() <= maxBytes` (else 413 `PAYLOAD_TOO_LARGE`).
    - Insert RUNNING `import_run` (user_id, source_name = `file.getOriginalFilename()`, preserveIds, idScope). Get runId.
    - **Extract on the request thread** (so a malformed zip / oversize fails fast with a clear response) to a temp dir `coldp.import.dir/{runId}/`; on `IOException` → `runs.fail(runId, msg)` + throw 400. Then fire `self.run(runId, dir, userId, preserveIds, idScope)` inside `try/catch (TaskRejectedException)` → `fail` + 503.
    - Return `ImportRunResponse.of(runs.findById(runId), objectMapper)`.
    - **Auth:** any authenticated user (they become OWNER of the created project — mirrors `POST /projects`). No project-role check (there is no project yet).
  - `run(long runId, Path dir, int userId, boolean preserveIds, String idScope)` — `@Async(ImportAsyncConfig.EXECUTOR_BEAN)`, `@Transactional`. **This task's body only does phases 1–2; Tasks 3–5 extend it.** Wrap in try/catch:
    1. `ColdpReader reader = ColdpReader.from(dir);`
    2. **Require** `reader.hasSchema(ColdpTerm.NameUsage) || (reader.hasSchema(ColdpTerm.Name) && reader.hasSchema(ColdpTerm.Taxon))` — else `throw new IllegalStateException("archive has neither a NameUsage file nor Name+Taxon files")`.
    3. `ColdpMetadataDto md = ColdpMetadata.read(dir);` (title may be null → default to the source filename stem).
    4. **nomCode from rows:** peek the first `NameUsage` (or `Taxon`) row's `ColdpTerm.code` via `reader.readFirstRow(...)`; `ColdpParse.parseEnum(NomCode.class, code)`.
    5. Create the project: `Project p = projectService.create(userId, new CreateProjectRequest(title, nomCode == null ? null : nomCode.name()));` then `projectService.updateMetadata(userId, p.getId(), new UpdateProjectMetadataRequest(title, md.alias(), md.description(), nomCode==null?null:nomCode.name(), md.license()==null?null:md.license(), md.geographicScope(), md.taxonomicScope(), null, null));` (license from metadata is already the SPDX wire form; `Licenses.parse` accepts it). `runs.setProject(runId, p.getId())`.
    6. `runs.finish(runId, 0, 0, 0, null)` (counts filled in later tasks).
    - On any `Exception`: `log.warn`, `runs.fail(runId, e.getMessage())`. **Cleanup:** best-effort delete the temp dir in a `finally`.
    - `@Transactional` means a fatal error rolls back the created project too (nothing committed) — matches the spec's "rollback + FAILED, delete nothing" contract. (Note: the RUNNING/FAILED row updates are separate short transactions via the mapper if `run` is `@Transactional` around the load only — keep the `runs.fail` update OUTSIDE the rolled-back load transaction so the FAILED status persists. Simplest: do the load work in a `self.loadTransactional(...)` `@Transactional` method the async `run` calls, and let `run` itself (non-transactional) own the try/catch + `runs.finish`/`runs.fail`. Mirror how `ColMatchJobService.runSync` (non-tx) calls `self.matchOneScope` (tx).)
  - `get(userId, runId)`: `findById`; 404 if missing or `run.getUserId() != userId` (never leak others' imports). `latest(userId)`: `findLatestByUser` (null → 204 at controller).

- [ ] **Step 3: `ImportRunController`** — `POST /api/projects/import` consumes `multipart/form-data` (`@RequestPart("file") MultipartFile`, `@RequestParam(defaultValue="false") boolean preserveIds`, `@RequestParam(required=false) String idScope`) → 202 + `ImportRunResponse`. `GET /api/projects/import/latest` (200/204), `GET /api/projects/import/{runId}` (200) — declare `/latest` so it out-ranks `/{runId}` (mirror the export controller comment). Resolve the current user id the same way other controllers do (grep an existing controller for the auth-principal → userId pattern).

- [ ] **Step 4: IT + clean verify + commit.** `ImportApiIT`:
  - Build a minimal archive in a temp dir (write `metadata.yaml` via `ColdpMetadata.write` with title/alias/license + a `NameUsage.tsv` with one accepted row incl. a `code` value, zip via `ColdpZip.zipFolder`), POST it as multipart → 202; poll `GET /{runId}` until DONE → assert a new project exists with the title/alias/license and `nomCode` from the row's `code`; `projectId` set on the run.
  - Missing-required-file archive (only metadata.yaml) → run FAILED with a clear error.
  - Oversize (size > a test-lowered `coldp.import.max-bytes`, or assert the cap logic via `ColdpZipTest`) → 413.
  - Clean verify. Commit `feat(coldp): async import job — archive open + project creation + endpoints`.

---

### Task 3: Load References + Authors (+ preserve-ids CURIEs + source-id maps)

**Files:** Modify `backend/.../coldp/imprt/ImportRunService.java` (extend the load); Test `backend/.../coldp/imprt/ImportRefAuthorIT.java`.

**Interfaces:**
- Consumes: `ReferenceMapper.insert`, `AuthorMapper.insert`, `IdSeqMapper.allocate`, `ReferenceColdpWriter`/`AuthorColdpWriter` (the mirror), `VerbatimRecord.get(ColdpTerm)`.
- Produces (in the load): `Map<String,Integer> refIdMap` (sourceRefId→newId), `Map<String,Integer> authorIdMap`, populated before names; the reference/author counts on the run.

- [ ] **Step 1: Reference load** — after project creation, before names: stream `reader.stream(ColdpTerm.Reference)`; for each row build a `Reference` inverting `ReferenceColdpWriter` (read that writer for the exact column↔field pairs — e.g. `citation←get(citation)`, `type←get(type)`, `accessed←get(accessed)`, `alternativeId←ColdpParse.csv(get(alternativeID))`, etc.). Allocate `r.setId(idSeq.allocate(pid,"reference"))`, `r.setProjectId(pid)`, `r.setModifiedBy(userId)`. **preserve-ids:** if `preserveIds`, add `idScope + ":" + get(ID)` to `r.getAlternativeId()` (create the list if null; skip if already present). `references.insert(r)`; `refIdMap.put(get(ID), r.getId())`. Count.

- [ ] **Step 2: Author load** — only if `reader.hasSchema(ColdpTerm.Author)`: same shape, inverting `AuthorColdpWriter`; `authorIdMap.put(get(ID), a.getId())`. Count.

- [ ] **Step 3: IT + clean verify + commit.** `ImportRefAuthorIT`: an archive with `Reference.tsv` (2 rows, incl. `accessed` + an `alternativeID` CURIE) + `Author.tsv` (1 row), imported with `preserveIds=true, idScope="src"` → assert both references created with all fields (incl. `accessed`), the author created, and each carries both its archive `alternativeID` CURIE **and** the `src:<sourceId>` preserved CURIE; run's `referenceCount==2`, `authorCount==1`. Clean verify. Commit `feat(coldp): import references + authors with source-id remap + preserve-ids CURIEs`.

---

### Task 4: Load names/usages — two-pass id-remap, status inverse, links, pro-parte re-merge, split form

**Files:** Modify `backend/.../coldp/imprt/ImportRunService.java`, `backend/.../name/NameUsageMapper.java` (add one hierarchy-update method); Create test fixtures + `backend/.../coldp/imprt/ImportNameUsageIT.java`, `ImportSplitFormIT.java`.

**Interfaces:**
- Consumes: `NameUsageMapper.insert`, `SynonymAcceptedMapper.link(pid,s,a,ordinal)`, `TaxonInfoMapper.upsert(...)`, `NameParserService.parseInto(NameUsage, NomCode)`, `IdSeqMapper.allocate(pid,"name_usage")`, `refIdMap`, `NameUsageColdpWriter` (mirror), `ColdpParse`.
- Produces: `Map<String,Integer> usageIdMap` (sourceUsageId→newId), used by Task 5; the name-usage count; `NameUsageMapper.updateHierarchy(...)` (below).

> **Why two-phase insert.** `name_usage.parent_id` and `basionym_id` are self-referential compound FKs (`FOREIGN KEY (project_id, parent_id) REFERENCES name_usage(project_id, id)`, V3/V8) and are **NOT deferrable**, so a row referencing a not-yet-inserted parent/basionym would fail at insert. Import can't assume topological order. Therefore: **insert every usage with `parent_id`/`basionym_id` NULL first** (Pass 1 — `published_in_reference_id`/`reference_id[]` CAN be set now since all references were inserted earlier in this same transaction), then in Pass 2, once every usage exists, **update** the hierarchy columns and create synonym links. `idSeq.allocate` is independent of the insert, so allocate up front to build the full `usageIdMap`.

- [ ] **Step 1: Add `NameUsageMapper.updateHierarchy`** — the only new mapper method:
```java
@Update("""
    UPDATE name_usage SET parent_id = #{parentId}, basionym_id = #{basionymId},
       version = version + 1, modified = now(), modified_by = #{modifiedBy}
    WHERE project_id = #{projectId} AND id = #{id}""")
int updateHierarchy(@Param("projectId") int projectId, @Param("id") int id,
    @Param("parentId") Integer parentId, @Param("basionymId") Integer basionymId,
    @Param("modifiedBy") int modifiedBy);
```

- [ ] **Step 2: Read the combined rows into memory** — stream `reader.stream(ColdpTerm.NameUsage)` into a `List<Map<ColdpTerm,String>>` (or a small holder). All rows must be visible before link resolution. For the **split form** (no NameUsage; Name+Taxon[+Synonym]) synthesize the same combined rows first — see Step 7.

- [ ] **Step 3: Detect pro-parte derived rows** — a row whose `ID` matches `^(\d+)-(\d+)$` where a row with `ID == group(1)` (the primary) **exists and has the same `scientificName`**. Partition into `primaryRows` (everything else, incl. the `<n>` primaries) and `proParteExtra` (the `<n>-<m>` rows). A `<n>-<m>` row with no matching primary, or a differing name, falls back to `primaryRows` (ordinary row — best-effort per spec).

- [ ] **Step 4: Pass 1 — build + insert one `NameUsage` per primary row, hierarchy NULL.** For each `primaryRow`:
  - `NameUsage u = new NameUsage(); u.setProjectId(pid); u.setId(idSeq.allocate(pid,"name_usage")); u.setModifiedBy(userId);` and `usageIdMap.put(get(ID), u.getId())`.
  - Invert `NameUsageColdpWriter.nameFields`/`acceptedRow` for the name/taxon fields: `scientificName`, `authorship`, `rank` (already lower-case string), `uninomial`, `genus←genericName`, `infragenericEpithet`, `specificEpithet`, `infraspecificEpithet`, `cultivarEpithet`, `notho←parseEnum(NamePart,notho)`, the 6 `combination*`/`basionym*` authorship fields, `namePhrase`, `publishedInYear←intOrNull(namePublishedInYear)`, `publishedInPage←namePublishedInPage`, `publishedInPageLink←namePublishedInPageLink`, `gender←parseEnum(Gender,gender)`, `etymology`, `nomStatus←parseEnum(NomStatus,nameStatus)`, `ordinal←intOrNull`, `remarks`, `alternativeId←csv(alternativeID)`, `extinct←Boolean.valueOf(extinct)` (accepted only), `environment←csv(environment).map(parseEnum(Environment,·))`, `temporalRangeStart/End`.
  - `u.setStatus(ColdpParse.parseStatus(get(status)))` (null → default `UNASSESSED`).
  - **published_in + reference_id (safe now — refs already inserted this tx):** `u.setPublishedInReferenceId(refIdMap.get(get(nameReferenceID)))`; `u.setReferenceId(csvInts(get(referenceID)).stream().map(i -> refIdMap.get(String.valueOf(i))).filter(Objects::nonNull).toList())`.
  - **hierarchy NULL:** leave `parentId`/`basionymId` null on the object.
  - **preserve-ids:** if `preserveIds`, add `idScope + ":" + get(ID)` to `u.getAlternativeId()`.
  - **parse:** after setting scientificName/authorship/rank, `nameParser.parseInto(u, projectNomCode)` — clears+repopulates atomized fields from scientificName+authorship (archive atomized columns are advisory) and re-derives `sanctioningAuthor` (a known loss), matching the create path. Never throws.
  - `usages.insert(u)`. For ACCEPTED rows with any of extinct/environment/temporal set → `taxonInfo.upsert(pid, u.getId(), u.getExtinct(), u.getEnvironment(), u.getTemporalRangeStart(), u.getTemporalRangeEnd())`.
  - Hold `(u, row)` for Pass 2.

- [ ] **Step 5: Pass 2 — resolve hierarchy + synonym links (all usages now exist).** For each held `(u, row)`:
  - `Integer parentNewId = usageIdMap.get(get(parentID))`, `Integer basionymNewId = usageIdMap.get(get(basionymID))`.
  - **status ACCEPTED:** `usages.updateHierarchy(pid, u.getId(), parentNewId, basionymNewId, userId)` (parent as classification parent). A non-blank `parentID`/`basionymID` not in the map → pass null + `ImportIssue("name_usage", get(ID), "parent/basionym <x> not found")`.
  - **status non-accepted (SYNONYM/MISAPPLIED/UNASSESSED):** parent is a **synonym link, not a parent_id** — `usages.updateHierarchy(pid, u.getId(), null, basionymNewId, userId)` then, if `parentNewId != null`, `synonymAccepted.link(pid, u.getId(), parentNewId, 0)`. This inverts the writer: an UNASSESSED-with-links usage was exported as `parentID`=accepted + `provisionally accepted` status, so its `parentID` re-imports as a synonym link. **Critical.** Unresolvable parentID → `ImportIssue`.

- [ ] **Step 6: Pro-parte re-merge** — for each `proParteExtra` row `<primaryId>-<acceptedId>`: `synonymAccepted.link(pid, usageIdMap.get(primaryId), usageIdMap.get(acceptedId), ordinal)` — an extra accepted link on the already-inserted primary synonym (incrementing ordinal per primary, starting after its primary link's 0). Dangling → skip + issue. Result: one `name_usage` for the synonym carrying N `synonym_accepted` links — reconstructs the pro-parte synonym.

- [ ] **Step 7: Split-form synthesis** — when the archive has `Name`+`Taxon`(+`Synonym`) instead of `NameUsage`: build combined rows by joining each `Taxon`/`Synonym` row to its `Name` (via `ColdpTerm.nameID` → `Name.ID`), copying the Name's name-level columns + the Taxon/Synonym's own columns into one synthetic combined row (status: Taxon→`accepted` unless it carries `provisionally accepted`; Synonym→`synonym`; a Synonym row's `taxonID` becomes the combined row's `parentID`). Our model is combined, so a Name used by N usages yields N `name_usage` rows. Feed these synthetic rows through Steps 3–6 unchanged.
- [ ] **Step 8: Tests + clean verify + commit.**
  - `ImportNameUsageIT`: a combined-form archive with an accepted chart (Animalia→…→Panthera + Panthera leo), 2 synonyms (one plain, one **pro parte** with a `42-<accId>` derived row), a basionym link, a `nameReferenceID` + a `referenceID` list → assert: correct usage count (pro-parte collapses to ONE synonym usage), parent_id chain resolved, the UNASSESSED-with-parent case becomes a synonym_accepted link (add such a row), basionym_id resolved, published_in + reference_id resolved to the remapped ref ids, the pro-parte synonym has 2 `synonym_accepted` links, a dangling parentID yields an `ImportIssue`.
  - `ImportSplitFormIT`: a small `Name.tsv`+`Taxon.tsv`+`Synonym.tsv` archive → assert the flattening to combined `name_usage` rows + synonym links.
  - Clean verify. Commit `feat(coldp): import name-usages — two-pass remap, status inverse, pro-parte re-merge, split form`.

---

### Task 5: Load child entities + finalize counts/issues + validate project

**Files:** Modify `backend/.../coldp/imprt/ImportRunService.java`; Test `backend/.../coldp/imprt/ImportChildEntitiesIT.java`.

**Interfaces:** Consumes the 7 child mappers' `insert(pid, id, usageId, Request, modifiedBy)`, `IdSeqMapper.allocate(pid, entity)`, `usageIdMap`, `refIdMap`, `ValidationService.revalidateProject(pid)`, `ChildColdpWriter` (mirror).

- [ ] **Step 1: Child load** — for each of the 7 files present, stream rows, invert the matching `ChildColdpWriter.*Row` method into the entity's `Request` DTO, resolve `taxonID`/`nameID` → `usageIdMap.get(...)` (dangling → skip + issue), `referenceID` → `refIdMap.get(...)`, allocate `idSeq.allocate(pid, entity)` and call the mapper's `insert(...)` directly (bypass `AbstractChildEntityService`). Distribution has no `ID` column (allocate one). Parse enum/vocab fields via `ColdpParse.parseEnum` where the writer lower-cased them; booleans (`preferred`, `extinct` handled in Task 4) via `Boolean.valueOf`; numbers via `intOrNull`/`Double.valueOf`.
- [ ] **Step 2: Finalize** — build the `issues` JSON array (`objectMapper.writeValueAsString(issues)`), call `runs.finish(runId, nameUsageCount, referenceCount, authorCount, issuesJson)`.
- [ ] **Step 3: Validate** — **after** the load transaction commits, call `validationService.revalidateProject(pid)` (it manages its own per-usage transactions via its `@Lazy self` — do NOT nest it inside the load transaction). Sequence in `run()`: `self.loadTransactional(...)` (all inserts, commits) → `runs.finish(...)` → `validationService.revalidateProject(pid)`.
- [ ] **Step 4: Tests + clean verify + commit.** `ImportChildEntitiesIT`: an archive with ≥1 of each child file (TypeMaterial w/ lat/lon + a `referenceID`, Distribution w/ area+gazetteer, VernacularName, Media, SpeciesEstimate, NameRelation between two usages, TaxonProperty) → assert each row created against the remapped usage/ref ids; a child row referencing an unknown taxonID → skipped + an `ImportIssue`; after import the project has validation issues surfaced (assert `revalidateProject` ran, e.g. a known rule fired). Clean verify. Commit `feat(coldp): import child entities + finalize counts/issues + revalidate project`.

---

### Task 6: Full round-trip IT + startup-sweep lifecycle IT

**Files:** Test `backend/.../coldp/imprt/ImportExportRoundTripIT.java`; possibly extend `ImportApiIT` for the sweep.

- [ ] **Step 1: Round-trip IT (the key test)** — seed the Felidae sample project (reuse the `ExportRoundTripIT` seeding helper — extract or share it), **export** it via `ExportRunService`/`ColdpWriter` to a temp zip, then **import** that zip with `preserveIds=true, idScope="orig"` → assert: a new project with matching entity counts (name_usages, references, authors, each child), parent_id chain + synonym links + basionym + published_in + reference_id all correctly resolved to the NEW project's ids, `taxon_info` round-tripped for accepted usages, each imported record carries an `orig:<sourceId>` alternativeID, and the combined+pro-parte NameUsage (if the sample has one) collapses correctly. Assert normalized whitespace where relevant.
- [ ] **Step 2: Startup stale-sweep IT** — insert a RUNNING `import_run`, invoke `ImportRunRecovery.onApplicationReady()` (or `runs.failStaleRunning()`), assert it flips to FAILED. (Mirror the export recovery test if one exists.)
- [ ] **Step 3: Clean verify + commit** `test(coldp): import↔export round-trip IT + startup stale-sweep`.

---

### Task 7: Frontend Import UI

**Files:** Create `frontend/src/api/import.ts`, `frontend/src/projects/ImportProjectModal.tsx` (or a section on the Projects list page); Modify `frontend/src/projects/ProjectListPage.tsx` (add the action); Test `frontend/src/projects/ImportProjectModal.test.tsx`.

**Interfaces:** `POST /api/projects/import` (multipart), `GET /api/projects/import/{runId}`, `GET /api/projects/import/latest`, `GET /api/coldp/id-scopes` (existing).

- [ ] **Step 1: `api/import.ts`** — `interface ImportRun {...}` mirroring `ImportRunResponse`; `startImport(file: File, preserveIds: boolean, idScope?: string): Promise<ImportRun>` (build `FormData`, POST — do NOT set a JSON `Content-Type`); `getImportRun(runId): Promise<ImportRun>`; `getLatestImport(): Promise<ImportRun | null>`. Reuse the id-scopes fetch from the export/id-scopes code if one exists.
- [ ] **Step 2: `ImportProjectModal`** — a file input (`.zip`), a "preserve source identifiers" `Switch`, and (when on) an id-scope `Select` (options from `/api/coldp/id-scopes` + a free-text custom entry, mirroring the Project-settings scope editor). On submit → `startImport` → poll (`refetchInterval` while RUNNING) → on DONE: a link to `/projects/{projectId}` + a non-fatal issues summary (count + list); on FAILED: the error. Reuse the poll pattern from `ProjectMetadataPage`'s export/match sections.
- [ ] **Step 3: Wire the action** — an **Import ColDP** button on the Projects list page opening the modal; on success invalidate the projects-list query so the new project appears.
- [ ] **Step 4: Tests + gates + commit.** `ImportProjectModal.test.tsx` (MSW): upload flow → poll → DONE shows the new-project link + issues; preserveIds toggles the scope select; FAILED shows the error. `npx vitest run` + `npx tsc --noEmit` + `npm run build`. Commit `feat(coldp): Import ColDP UI (upload + preserve-ids + poll + new-project link)`.

---

## Self-Review notes
- **Spec coverage:** import_run + async/recovery = T1; upload+parse+project+endpoints = T2; refs/authors+preserve-ids = T3; two-pass names + status inverse + pro-parte re-merge + split form = T4; child entities + validate = T5; round-trip + lifecycle ITs = T6; Import UI = T7. Non-goals (merge, other formats) stay out.
- **Reverse-mapping fidelity:** every column is inverted from the matching export writer (the mirror), not guessed. Vocab/status inverse + comma-split centralized in `ColdpParse` (T1), unit-tested against the writer's transforms.
- **Type consistency:** `usageIdMap`/`refIdMap`/`authorIdMap` are `Map<String,Integer>` (source ColDP ID string → our numeric id) threaded T3→T4→T5. Id allocation always `idSeq.allocate(pid, entity)`. Links via `SynonymAcceptedMapper.link(pid,s,a,ordinal)` / `parent_id` per status (the UNASSESSED→synonym-link inverse is the subtle correctness point). `runs.finish(runId, nUsage, nRef, nAuthor, issuesJson)` signature fixed in T1, filled in T5.
- **Transaction shape:** the load runs in one `@Transactional` method (`self.loadTransactional`) so a fatal error rolls back the whole project; `runs.finish`/`fail` and `revalidateProject` run OUTSIDE it (mirrors `ColMatchJobService.runSync` non-tx wrapping tx `matchOneScope`). Temp dir cleaned in `finally`.
- **Auth/security:** any authenticated user imports (becomes OWNER); size cap + zip-slip + entry-count guard on extract; `ColdpMetadata.read` already `SafeConstructor`-hardened; `get`/`latest` scoped to the requesting user.
- **Known losses** (sanctioning_author via parseInto, occurrenceId, audit modified) explicitly not expected on import.
