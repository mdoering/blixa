# ColDP Foundation + IO Primitives — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prepare the ground for ColDP export/import: drop the now-redundant `coldp_id` column, pull in the CLB `reader` dependency (proving it coexists with Spring Boot), and build the small IO primitives (zip, term-keyed TSV read/write, `metadata.yaml`, id-scope vocab) that the Export and Import plans build on.

**Architecture:** Reuse CLB's ColDP io (`org.catalogueoflife:reader` → `ColdpReader`, `ColdpTerm`, `TermWriter`/`TabWriter`, `Identifier.Scope`) rather than hand-rolling parsing. Identity is our per-project numeric `id`; external ids are `alternativeID` CURIEs (no `coldp_id`).

**Tech Stack:** Spring Boot 4.1 / Java 25 / Postgres 17 / MyBatis / Flyway; `org.catalogueoflife:reader:1.2.3-SNAPSHOT` (+ transitive `api`, `coldp`); SnakeYAML (present).

## Global Constraints

- Build/test ONLY with JDK 25: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/25.0.1-librca ./mvnw ...` (default java 21 won't compile). No Testcontainers `api.version` workaround.
- Commit directly to `main` (no branches). One commit per task after its tests pass.
- The GBIF snapshots repo is already in `pom.xml` (it resolves the existing `vocab` dep); the `reader` dep uses the **same version as `vocab` — `1.2.3-SNAPSHOT`**.
- Spec: `docs/superpowers/specs/2026-07-10-coldp-export-import-design.md`.
- CLB io facts (verified against `~/code/col/backend`; you may read that source):
  - `ColdpTerm` (enum, `implements org.gbif.dwc.terms.Term`): file/class terms `Reference, Name, Taxon, Synonym, NameUsage, Author, NameRelation, TypeMaterial, TaxonProperty, Distribution, Media, VernacularName, SpeciesEstimate, …`. `ColdpTerm.RESOURCES` is `Map<ColdpTerm,List<ColdpTerm>>` = the canonical ordered columns per file. `term.simpleName()` = bare column name (e.g. `scientificName`); `term.prefixedName()` = `col:scientificName`.
  - Read: `ColdpReader.from(Path folder)` — needs an **extracted directory** (no zip factory). `reader.hasSchema(ColdpTerm.X)`, `reader.stream(ColdpTerm.X)` → `Stream<VerbatimRecord>`; `record.get(ColdpTerm.y)` → String. `stream()` requires a class term.
  - Write: `TabWriter.fromFile(File)` / `new TabWriter(Writer)` → `write(String[])`, TAB-delimited, escapes tab/newline. `TermWriter(RowWriter, rowType, cols)` writes a `col:`-prefixed header in its constructor; `set(Term,String)`, `next()`.
  - `life.catalogue.api.model.Identifier.Scope` values: `LOCAL, DOI, URL, URN, LSID, COL, GBIF, WFO, TPL, TSN, IPNI, IF, ZOOBANK, INAT, INA`; `Scope.prefix()` = lowercase name. `Identifier.parse(String)` splits a `scope:id` CURIE.

---

### Task 1: Drop `coldp_id`

**Files:**
- Create: `backend/src/main/resources/db/migration/V14__drop_coldp_id.sql`
- Modify: `backend/.../name/NameUsage.java`, `Reference.java`, `Author.java` (remove the `coldpId` field + getter/setter)
- Modify: `backend/.../name/NameUsageMapper.java` (INSERT col list + values, UPDATE SET), `ReferenceMapper.java`, `AuthorMapper.java`
- Modify: `backend/src/test/.../name/NameUsageMapperIT.java` (remove coldp_id usage/asserts)

**Interfaces:**
- Produces: `name_usage`, `reference`, `author` no longer have `coldp_id`; the POJOs lose `coldpId`. External ids continue to live in `alternative_id`.

- [ ] **Step 1: Write the migration**

`V14__drop_coldp_id.sql`:
```sql
-- coldp_id is redundant: identity is the per-project numeric id; external ids live in alternative_id.
-- DROP COLUMN also removes the UNIQUE (project_id, coldp_id) constraints defined in V3.
ALTER TABLE name_usage DROP COLUMN coldp_id;
ALTER TABLE reference  DROP COLUMN coldp_id;
ALTER TABLE author     DROP COLUMN coldp_id;
```

- [ ] **Step 2: Remove `coldpId` from the three POJOs**

In `NameUsage.java`, `Reference.java`, `Author.java` delete `private String coldpId;` and its `getColdpId()`/`setColdpId()`.

- [ ] **Step 3: Remove `coldp_id` from the three mappers**

`NameUsageMapper.java`: drop `coldp_id,` from the INSERT column list (~line 25) and `#{coldpId},` from VALUES (~line 35), and the `SET coldp_id = #{coldpId},` line in the UPDATE (~line 259). Same for `ReferenceMapper.java` (INSERT ~23/26, UPDATE ~65) and `AuthorMapper.java` (INSERT ~23/26, UPDATE ~50). Leave `alternative_id` untouched.

- [ ] **Step 4: Clean the mapper IT**

In `NameUsageMapperIT.java`, remove any `setColdpId(...)` / `getColdpId()` / coldp_id assertions (search the file).

- [ ] **Step 5: Full verify + commit**

Run: `JAVA_HOME=~/.sdkman/candidates/java/25.0.1-librca ./mvnw verify` → BUILD SUCCESS (report unit + IT counts).
Commit: `refactor(name): drop redundant coldp_id (identity = numeric id + alternativeID CURIEs)`.

---

### Task 2: Add the CLB `reader` dependency (prove Spring context still loads)

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/test/.../coldp/ColdpDependencyIT.java`

**Interfaces:**
- Produces: `org.catalogueoflife:reader:1.2.3-SNAPSHOT` on the classpath — `ColdpTerm`, `ColdpReader`, `TabWriter`/`TermWriter`, `Identifier.Scope` all importable. Consumed by every later task.

**This is the main risk task** — `reader` drags in `api` (Jackson 2, guava, commons, httpclient5, mapdb, kryo, fastutil). Verify the Spring context still starts and the suite stays green.

- [ ] **Step 1: Add the dependency**

In `pom.xml`, next to the existing `vocab` dependency:
```xml
<dependency>
  <groupId>org.catalogueoflife</groupId>
  <artifactId>reader</artifactId>
  <version>1.2.3-SNAPSHOT</version>
  <exclusions>
    <!-- test engines / logging brought transitively; Spring Boot manages logback + JUnit 5 already -->
    <exclusion><groupId>org.junit.vintage</groupId><artifactId>junit-vintage-engine</artifactId></exclusion>
    <exclusion><groupId>ch.qos.logback</groupId><artifactId>logback-classic</artifactId></exclusion>
  </exclusions>
</dependency>
```
(If `mvn` reports the version isn't resolvable, run `JAVA_HOME=… ./mvnw dependency:get -Dartifact=org.catalogueoflife:reader:1.2.3-SNAPSHOT` or check `~/.m2`; the GBIF snapshots repo is already configured. If a different snapshot version is what actually resolves, use that and note it.)

- [ ] **Step 2: Write the compile+context proof IT**

`ColdpDependencyIT` (extends the existing context-loading base — mirror `DefaultProfileContextLoadsIT` or `AbstractPostgresIT`, whichever gives a full context): a `@Test` that references the dependency types so a break fails at compile/run:
```java
assertThat(ColdpTerm.NameUsage.isClass()).isTrue();
assertThat(ColdpTerm.RESOURCES.get(ColdpTerm.Reference)).contains(ColdpTerm.ID);
assertThat(Identifier.Scope.COL.prefix()).isEqualTo("col");
// TabWriter/TermWriter/ColdpReader are importable (compile-time reference is enough):
assertThat(TabWriter.class).isNotNull();
assertThat(ColdpReader.class).isNotNull();
```
The `@SpringBootTest`/context-load part is what proves no bean/classpath breakage from `api`'s transitive jars.

- [ ] **Step 3: Full verify + commit**

Run: `JAVA_HOME=… ./mvnw verify` → BUILD SUCCESS. **If the context fails to load** (a transitive-dep clash — e.g. a conflicting library version pulled onto the classpath), diagnose and add targeted `<exclusion>`s or a `dependencyManagement` pin; do NOT downgrade Spring Boot or our Jackson 3. If it can't be reconciled, STOP and report BLOCKED with the specific clash (the fallback is depending only on `coldp` for `ColdpTerm` + hand-rolling io — a plan change).
Commit: `build: add org.catalogueoflife:reader for ColDP io (ColdpTerm/ColdpReader/TermWriter)`.

---

### Task 3: Zip archive helper (write a folder → zip; extract a zip → temp folder)

**Files:**
- Create: `backend/.../coldp/io/ColdpZip.java`
- Test: `backend/src/test/.../coldp/io/ColdpZipTest.java`

**Interfaces:**
- Produces:
  - `static void ColdpZip.zipFolder(Path folder, Path targetZip)` — zip every regular file directly under `folder` (flat; ColDP files at the archive root) into `targetZip`.
  - `static Path ColdpZip.extractToTemp(InputStream zip, Path tempDir)` — extract entries into `tempDir` (guard against zip-slip: reject entries whose resolved path escapes `tempDir`), return `tempDir`. Consumed by Export (zip) and Import (extract).

- [ ] **Step 1: Write the failing test**

`ColdpZipTest` (plain JUnit + `@TempDir`): create a temp folder with two files (`NameUsage.tsv`, `metadata.yaml`), `zipFolder(...)`, then `extractToTemp(new FileInputStream(zip), otherTemp)`, assert both files exist with identical content. Add a zip-slip test: a crafted entry name `../evil` must be rejected (throws), not written outside the temp dir.

- [ ] **Step 2: Run — FAIL. Step 3: Implement `ColdpZip`** with `java.util.zip.ZipOutputStream`/`ZipInputStream`; normalize + validate each extracted entry path with `tempDir.resolve(name).normalize().startsWith(tempDir)`.

- [ ] **Step 4: Run — PASS. Commit** `feat(coldp): zip/extract archive helper (zip-slip safe)`.

---

### Task 4: Term-keyed TSV IO primitive

**Files:**
- Create: `backend/.../coldp/io/ColdpTsv.java`
- Test: `backend/src/test/.../coldp/io/ColdpTsvIT.java`

**Interfaces:**
- Produces:
  - `static void ColdpTsv.writeFile(Path dir, ColdpTerm fileTerm, Iterable<Map<ColdpTerm,String>> rows)` — writes `dir/{fileTerm.simpleName()}.tsv` with a **bare** header (`ColdpTerm.RESOURCES.get(fileTerm)` mapped through `simpleName()`) via `TabWriter`; each row is the values in that column order (missing key → empty). Bare headers maximise interop; `ColdpReader` reads them.
  - Reading is `ColdpReader.from(dir)` directly (library) — no wrapper needed; this task proves our written files read back.
- Consumed by: Export (write) and Import (read).

- [ ] **Step 1: Write the round-trip IT** (`ColdpTsvIT`, `@TempDir`; can be a plain unit test if no Spring needed): build two `Map<ColdpTerm,String>` rows for `ColdpTerm.NameUsage` (ID, scientificName, rank, status), `writeFile(dir, NameUsage, rows)`; then `try (var r = ColdpReader.from(dir))` and `r.stream(ColdpTerm.NameUsage)` → collect; assert 2 rows and `rec.get(ColdpTerm.scientificName)` etc. match (real round-trip through the CLB reader). Include a value needing escaping (a tab or newline) to prove TabWriter escaping + reader unescaping.

- [ ] **Step 2: Run — FAIL. Step 3: Implement `ColdpTsv.writeFile`**: `cols = ColdpTerm.RESOURCES.get(fileTerm)`; open `TabWriter.fromFile(dir.resolve(fileTerm.simpleName()+".tsv").toFile())`; write header `cols.stream().map(ColdpTerm::simpleName).toArray(String[]::new)`; per row write `cols.stream().map(t -> row.getOrDefault(t, "")).toArray(...)`; close.

- [ ] **Step 4: Run — PASS. Commit** `feat(coldp): term-keyed TSV writer round-tripping through ColdpReader`.

---

### Task 5: `metadata.yaml` read/write

**Files:**
- Create: `backend/.../coldp/io/ColdpMetadata.java`
- Test: `backend/src/test/.../coldp/io/ColdpMetadataTest.java`

**Interfaces:**
- Produces:
  - `record ColdpMetadataDto(String title, String alias, String description, String code, String license, String geographicScope, String taxonomicScope)`.
  - `static void ColdpMetadata.write(Path dir, ColdpMetadataDto md)` → `dir/metadata.yaml` (SnakeYAML).
  - `static ColdpMetadataDto ColdpMetadata.read(Path dir)` → parse `dir/metadata.yaml` (tolerant of missing keys; unknown keys ignored). Consumed by Export (write from project) and Import (read into a new project).

- [ ] **Step 1: Write the round-trip test** (`ColdpMetadataTest`, `@TempDir`): `write` a dto, `read` it back, assert equality; and a `read` of a hand-written minimal `metadata.yaml` fixture (title + code only) → other fields null.
- [ ] **Step 2: Run — FAIL. Step 3: Implement** using `org.yaml.snakeyaml.Yaml` (on the classpath via Spring Boot). Map our fields to ColDP metadata keys (`title`, `alias`, `description`, `code`, `license`, `geographicScope`, `taxonomicScope`); verify key names against the coldp `metadata.yaml`/`ColdpTerm` conventions.
- [ ] **Step 4: Run — PASS. Commit** `feat(coldp): metadata.yaml read/write`.

---

### Task 6: Id-scope vocab endpoint

**Files:**
- Create: `backend/.../coldp/IdScopeController.java`
- Test: `backend/src/test/.../coldp/IdScopeIT.java`

**Interfaces:**
- Produces: `GET /api/coldp/id-scopes` → `List<String>` of `Identifier.Scope` prefixes (`col, gbif, wfo, tpl, tsn, ipni, if, zoobank, inat, ina, doi, url, urn, lsid, local`), authenticated read. Consumed by the Import UI (Plan 3) for the "preserve ids under scope" dropdown.

- [ ] **Step 1: Write the failing IT** (`IdScopeIT`, `@AutoConfigureMockMvc` + a mock user like the other API ITs): `GET /api/coldp/id-scopes` → 200, `$` contains `"col"` and `"gbif"`, length == `Identifier.Scope.values().length`.
- [ ] **Step 2: Run — FAIL. Step 3: Implement** a `@RestController` returning `Arrays.stream(Identifier.Scope.values()).map(Identifier.Scope::prefix).toList()`.
- [ ] **Step 4: Run — PASS. Commit** `feat(coldp): GET /api/coldp/id-scopes (Identifier.Scope vocab)`.

---

## Self-Review notes
- Spec coverage: Phase 0 (drop coldp_id) = Task 1; Phase 1 (format core: dependency, zip, TSV io, metadata, scopes) = Tasks 2–6. The per-entity record↔row mapping is deliberately deferred to the Export (write direction) and Import (read direction) plans, which own their direction over these primitives.
- Risk front-loaded: Task 2 proves the heavy `api`/`reader` transitive classpath coexists with Spring Boot before any feature work; a BLOCKED here changes the plan (fallback: `coldp`-only + hand-rolled io).
- Type consistency: `ColdpTsv.writeFile`, `ColdpZip`, `ColdpMetadata` are all keyed to `Path`/`ColdpTerm`, consumed uniformly by Export/Import. `ColdpMetadataDto` fields line up with `project` columns (title/alias/description/nom_code→code/license/geographic_scope/taxonomic_scope).
- Verify during Task 1: confirm no DTO (`NameUsageResponse`/`ReferenceResponse`) or `DevSampleData` references `coldpId` (grep showed none — reconfirm at edit time).
