# Phase 1 — Domain Model & Core-Entity API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The core taxonomic backend — the exact `reference` / `author` / `name_usage` / `synonym_accepted` domain classes and Flyway schema, GBIF name-parser 4.2.0 integration (parse + render), and REST CRUD + fuzzy search for References and NameUsages — all project-scoped.

**Architecture:** Extends the Plan 1 Spring Boot backend. The domain model is the collapsed `name_usage` entity (name facts + taxonomic usage in one row, per the design spec §5.4), with synonymy in a `synonym_accepted` relation table (§5.5). Name strings are authoritative; the GBIF **name-parser 4.2.0** atomizes them into structured columns on entry and **NameFormatter** renders them, both keyed to the project's `nom_code`. Vocabulary enums are reused from `name-parser-api` (`Rank`, `NomCode`, `NamePart`, `NameType`) and `org.catalogueoflife:vocab` (`TaxonomicStatus`, `NomStatus`, …). Fuzzy search uses Postgres `pg_trgm`. Committed directly to `main` (no branches).

**Tech Stack:** Java 21, Spring Boot, MyBatis, Postgres 17 + `pg_trgm`, Flyway, `org.gbif:name-parser:4.2.0` (+ `name-parser-api`), `org.catalogueoflife:vocab:1.2.3-SNAPSHOT` (+ transitive `coldp`), Testcontainers.

> **Stack update (2026-07-08, post-execution):** the backend now runs on **Spring Boot 4.1.0 / Java 25** (upgraded after Boot 3.5 EOL). The `env 'api.version=1.41'` OrbStack Testcontainers workaround referenced throughout this plan's steps is **no longer needed** (Testcontainers 2.0 negotiates the Docker API correctly); run `mvn`/`mvn verify` under **JDK 25** (`backend/.sdkmanrc` → `25.0.1-librca`, `sdk env`). Authoritative current stack: design spec §3.

## Global Constraints

- Extends the existing backend under `backend/`, base package `org.catalogueoflife.editor`. Commit to `main`; no branches.
- MyBatis hand-written SQL (annotation mappers); no JPA. Surrogate `BIGINT GENERATED ALWAYS AS IDENTITY` PKs; every data table carries `project_id` (FK to `project`, `ON DELETE CASCADE`).
- **Collapsed model:** one `name_usage` row holds name + usage. `parent_id` links **accepted** usages into the tree; synonyms link to accepted taxa via `synonym_accepted` (NOT `parent_id`). No flat higher-rank columns. No `according_to`. No scrutinizer columns (derived from audit later).
- **Name-parser 4.2.0 contract** (design spec Appendix A): instantiate `new NameParserImpl()`; call `parse(scientificName, authorship, Rank, NomCode)`; read name-level authorship via `getCombinationAuthorship()` / `getBasionymAuthorship()` / `getSanctioningAuthor()` (ignore `genericAuthorship`/`specificAuthorship`); render with `NameFormatter`. Reuse enums from `name-parser-api` + `org.catalogueoflife:vocab` — do NOT define parallel enums.
- Strings authoritative: store the full `scientific_name` + `authorship` always; parsed fields are derived and re-derivable. Bad input is allowed (parse failures are stored as `NameType`/flags, never rejected).
- `nom_code` is per-project (Plan 1 stores it as text); parse/format use it. Bind it to `NomCode` here.
- Every task ends with its integration test(s) green (run via `env 'api.version=1.41' mvn -q -Dtest=... test` on this machine — the OrbStack Docker workaround) and a commit. `mvn verify` runs the full `*IT` suite via the Plan-1 Failsafe plugin.
- Add the GBIF Maven repositories to the pom so the CoL/GBIF artifacts resolve on any machine.

## File Structure

```
backend/
  pom.xml                                              # + name-parser, vocab deps; + GBIF repos
  src/main/resources/db/migration/
    V3__name_core.sql                                  # reference, author, name_usage, synonym_accepted + pg_trgm
  src/main/java/org/catalogueoflife/editor/
    name/
      Reference.java                                   # POJO
      ReferenceMapper.java
      ReferenceService.java
      ReferenceController.java
      Author.java
      AuthorMapper.java
      NameUsage.java                                   # collapsed name+usage POJO
      NameUsageMapper.java
      NameUsageService.java
      NameUsageController.java
      SynonymAcceptedMapper.java
      dto/ ...                                         # request/response records
    parse/
      NameParserService.java                           # wraps NameParserImpl + NameFormatter
      ParsedNameMapping.java                            # ParsedName -> NameUsage fields
  src/test/java/org/catalogueoflife/editor/
    name/ReferenceMapperIT.java, NameUsageMapperIT.java, ReferenceApiIT.java, NameUsageApiIT.java
    parse/NameParserServiceIT.java (or plain unit test)
```

---

### Task 1: Dependencies, schema, and the core domain entities

**Files:**
- Modify: `backend/pom.xml` (add deps + GBIF repos)
- Create: `backend/src/main/resources/db/migration/V3__name_core.sql`
- Create: `backend/src/main/java/org/catalogueoflife/editor/name/Reference.java`, `Author.java`, `NameUsage.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/name/ReferenceMapper.java`, `AuthorMapper.java`, `NameUsageMapper.java`, `SynonymAcceptedMapper.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/name/NameUsageMapperIT.java`

**Interfaces:**
- Produces: the DDL and POJOs/mappers below. `NameUsageMapper.insert(NameUsage)` sets the generated `id`; `findById(long)`; `findByProject(long projectId, int limit, int offset)`; `SynonymAcceptedMapper.link(synonymUsageId, acceptedUsageId, ordinal)`, `findAcceptedFor(synonymUsageId)`, `findSynonymsOf(acceptedUsageId)`.

- [ ] **Step 1: Add dependencies and GBIF repositories to the pom**

In `backend/pom.xml`, add to `<dependencies>`:

```xml
    <dependency>
      <groupId>org.gbif</groupId>
      <artifactId>name-parser</artifactId>
      <version>4.2.0</version>
    </dependency>
    <dependency>
      <groupId>org.gbif</groupId>
      <artifactId>name-parser-api</artifactId>
      <version>4.2.0</version>
    </dependency>
    <dependency>
      <groupId>org.catalogueoflife</groupId>
      <artifactId>vocab</artifactId>
      <version>1.2.3-SNAPSHOT</version>
    </dependency>
```

And add repositories (after `</dependencies>`):

```xml
  <repositories>
    <repository>
      <id>gbif-releases</id>
      <url>https://repository.gbif.org/repository/releases/</url>
    </repository>
    <repository>
      <id>gbif-snapshots</id>
      <snapshots><enabled>true</enabled></snapshots>
      <url>https://repository.gbif.org/repository/snapshots/</url>
    </repository>
  </repositories>
```

Verify resolution: `cd backend && mvn -q dependency:resolve 2>&1 | tail -5` (the artifacts are already in the local `.m2`; this confirms the pom is well-formed).

- [ ] **Step 2: Create the migration**

`backend/src/main/resources/db/migration/V3__name_core.sql`:

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE reference (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id     BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  coldp_id       TEXT,                       -- original ColDP Reference.ID (for round-trip)
  alternative_id TEXT[],
  citation       TEXT,
  type           TEXT,                       -- CSL type
  author         TEXT,
  editor         TEXT,
  title          TEXT,
  container_title TEXT,
  issued         TEXT,
  volume         TEXT,
  issue          TEXT,
  page           TEXT,
  publisher      TEXT,
  doi            TEXT,
  isbn           TEXT,
  issn           TEXT,
  link           TEXT,
  remarks        TEXT,
  modified       TIMESTAMPTZ NOT NULL DEFAULT now(),
  modified_by    BIGINT REFERENCES app_user(id),
  version        INTEGER NOT NULL DEFAULT 0,
  UNIQUE (project_id, coldp_id)
);
CREATE INDEX reference_project_idx ON reference (project_id);
CREATE INDEX reference_citation_trgm ON reference USING gin (citation gin_trgm_ops);

CREATE TABLE author (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id     BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  coldp_id       TEXT,
  alternative_id TEXT[],
  given          TEXT,
  family         TEXT,
  suffix         TEXT,
  abbreviation_botany TEXT,
  affiliation    TEXT,
  birth          TEXT,
  death          TEXT,
  birth_place    TEXT,
  country        TEXT,
  link           TEXT,
  remarks        TEXT,
  modified       TIMESTAMPTZ NOT NULL DEFAULT now(),
  modified_by    BIGINT REFERENCES app_user(id),
  version        INTEGER NOT NULL DEFAULT 0,
  UNIQUE (project_id, coldp_id)
);
CREATE INDEX author_project_idx ON author (project_id);

CREATE TABLE name_usage (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id     BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  coldp_id       TEXT,
  alternative_id TEXT[],
  parent_id      BIGINT REFERENCES name_usage(id) ON DELETE SET NULL,   -- accepted classification tree only
  basionym_id    BIGINT REFERENCES name_usage(id) ON DELETE SET NULL,
  ordinal        INTEGER,
  -- taxonomic
  status         TEXT NOT NULL,             -- TaxonomicStatus enum name
  name_phrase    TEXT,
  reference_id   BIGINT[],                  -- taxonomic references
  extinct        BOOLEAN,
  environment    TEXT[],
  temporal_range_start TEXT,
  temporal_range_end   TEXT,
  -- nomenclatural (name)
  scientific_name TEXT NOT NULL,
  authorship     TEXT,
  rank           TEXT NOT NULL,             -- Rank enum name
  uninomial      TEXT,
  genus          TEXT,
  infrageneric_epithet TEXT,
  specific_epithet     TEXT,
  infraspecific_epithet TEXT,
  cultivar_epithet TEXT,
  notho          TEXT,
  combination_authorship TEXT,
  combination_ex_authorship TEXT,
  combination_authorship_year TEXT,
  basionym_authorship TEXT,
  basionym_ex_authorship TEXT,
  basionym_authorship_year TEXT,
  sanctioning_author TEXT,
  nom_status     TEXT,                      -- NomStatus enum name
  published_in_reference_id BIGINT REFERENCES reference(id) ON DELETE SET NULL,
  published_in_year TEXT,
  published_in_page TEXT,
  published_in_page_link TEXT,
  gender         TEXT,
  etymology      TEXT,
  name_type      TEXT,                      -- NameType from the parser (e.g. SCIENTIFIC, VIRUS, PLACEHOLDER)
  parse_state    TEXT,                      -- ParsedName.State (COMPLETE/PARTIAL/...) or 'UNPARSABLE'
  link           TEXT,
  remarks        TEXT,
  modified       TIMESTAMPTZ NOT NULL DEFAULT now(),
  modified_by    BIGINT REFERENCES app_user(id),
  version        INTEGER NOT NULL DEFAULT 0,
  UNIQUE (project_id, coldp_id)
);
CREATE INDEX name_usage_project_idx ON name_usage (project_id);
CREATE INDEX name_usage_parent_idx ON name_usage (project_id, parent_id);
CREATE INDEX name_usage_sciname_trgm ON name_usage USING gin (scientific_name gin_trgm_ops);

CREATE TABLE synonym_accepted (
  synonym_usage_id  BIGINT NOT NULL REFERENCES name_usage(id) ON DELETE CASCADE,
  accepted_usage_id BIGINT NOT NULL REFERENCES name_usage(id) ON DELETE CASCADE,
  ordinal           INTEGER,
  PRIMARY KEY (synonym_usage_id, accepted_usage_id)
);
CREATE INDEX synonym_accepted_accepted_idx ON synonym_accepted (accepted_usage_id);
```

- [ ] **Step 3: Create the POJOs**

Create `Reference.java`, `Author.java`, `NameUsage.java` as plain POJOs with fields matching the columns (camelCase; `TEXT[]` → `List<String>` via MyBatis; `reference_id BIGINT[]` → `List<Long>`), each with getters/setters and a `version` field. (Full field lists mirror the DDL above; use `java.util.List<String>` for array columns and register MyBatis to map `text[]`/`bigint[]` — see Step 4.)

Minimal `NameUsage.java` field set (getters/setters elided here for brevity — include them all):

```java
package org.catalogueoflife.editor.name;

import java.time.OffsetDateTime;
import java.util.List;

public class NameUsage {
  private Long id;
  private Long projectId;
  private String coldpId;
  private List<String> alternativeId;
  private Long parentId;
  private Long basionymId;
  private Integer ordinal;
  private String status;
  private String namePhrase;
  private List<Long> referenceId;
  private Boolean extinct;
  private List<String> environment;
  private String temporalRangeStart;
  private String temporalRangeEnd;
  private String scientificName;
  private String authorship;
  private String rank;
  private String uninomial;
  private String genus;
  private String infragenericEpithet;
  private String specificEpithet;
  private String infraspecificEpithet;
  private String cultivarEpithet;
  private String notho;
  private String combinationAuthorship;
  private String combinationExAuthorship;
  private String combinationAuthorshipYear;
  private String basionymAuthorship;
  private String basionymExAuthorship;
  private String basionymAuthorshipYear;
  private String sanctioningAuthor;
  private String nomStatus;
  private Long publishedInReferenceId;
  private String publishedInYear;
  private String publishedInPage;
  private String publishedInPageLink;
  private String gender;
  private String etymology;
  private String nameType;
  private String parseState;
  private String link;
  private String remarks;
  private OffsetDateTime modified;
  private Long modifiedBy;
  private Integer version;
  // getters and setters for every field
}
```

- [ ] **Step 4: Create the mappers with array handling**

Array columns need MyBatis typehandlers. For Postgres `text[]`/`bigint[]`, use `org.apache.ibatis.type.ArrayTypeHandler` on those properties in the `@Result`/insert. Provide the mappers as `@Mapper` interfaces using explicit column↔property mapping for the array fields. Example `NameUsageMapper` insert (arrays passed via a small helper that wraps `java.sql.Array`; the simplest reliable approach is a MyBatis `@Insert` with `jdbcType=ARRAY` and `typeHandler=ArrayTypeHandler`, or a custom `StringArrayTypeHandler`). Create `StringArrayTypeHandler` and `LongArrayTypeHandler` under `name/` and register them, then map the array columns with `typeHandler=`. Full mapper SQL lists every column; `@Options(useGeneratedKeys=true, keyProperty="id")` on inserts.

(The implementer writes the complete `ReferenceMapper`, `AuthorMapper`, `NameUsageMapper`, and `SynonymAcceptedMapper` following the Plan 1 mapper style, adding the array typehandlers. `SynonymAcceptedMapper`: `void link(@Param("s") long synId, @Param("a") long accId, @Param("o") Integer ordinal)` with `ON CONFLICT DO NOTHING`; `List<Long> findAcceptedFor(long synId)`; `List<Long> findSynonymsOf(long accId)`.)

- [ ] **Step 5: Write the failing mapper test**

`NameUsageMapperIT.java` (extends `AbstractPostgresIT`): seed a `project` + `app_user`, insert an accepted `name_usage` ("Abies alba Mill.", rank species, status accepted), insert a synonym usage, `synonymAccepted.link(synId, accId, 0)`, then assert: generated ids set; `findById` round-trips arrays (`alternativeId`, `referenceId`); `findSynonymsOf(accId)` returns `[synId]`; `findAcceptedFor(synId)` returns `[accId]`.

Run: `cd backend && env 'api.version=1.41' mvn -q -Dtest=NameUsageMapperIT test` → PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/pom.xml backend/src && git commit -m "feat(backend): name-core schema + reference/author/name_usage/synonym_accepted entities"
```

---

### Task 2: Name-parser 4.2.0 integration (parse + render)

**Files:**
- Create: `backend/src/main/java/org/catalogueoflife/editor/parse/NameParserService.java`, `ParsedNameMapping.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/parse/NameParserServiceTest.java`

**Interfaces:**
- Produces:
  - `NameParserService` (a `@Service` holding one `new NameParserImpl()`): `void parseInto(NameUsage u, String nomCode)` — parses `u.scientificName` + `u.authorship` with the project's `NomCode` and the usage's `Rank`, populating the atomized fields (`genus`, `specificEpithet`, …, `combinationAuthorship`+year, `basionymAuthorship`+year, `sanctioningAuthor`, `notho`), `nameType`, and `parseState`; on `UnparsableNameException` sets `parseState='UNPARSABLE'` and `nameType` from the exception (never throws to the caller). `String formatName(NameUsage u, String nomCode, boolean html)` — renders via `NameFormatter`.
  - `ParsedNameMapping` — static `applyTo(ParsedName pn, NameUsage u)` mapping per design-spec Appendix A (name-level `getCombinationAuthorship()`/`getBasionymAuthorship()` only; ignore generic/specific authorship).

- [ ] **Step 1: Implement the parser service** — follow design spec Appendix A exactly. `Rank`/`NomCode` parsed from the strings via the enums' own parse helpers (`NomCode.valueOf` on the project code uppercased, tolerant; `Rank` from `u.rank`). Authorship: `pn.getCombinationAuthorship().getAuthors()` (pipe-join) → `combinationAuthorship`, `.getExAuthors()` → `combinationExAuthorship`, `.getYear()` → `combinationAuthorshipYear`; same for basionym; `pn.getSanctioningAuthor()`; `notho` from `pn.getNotho()` (join the `Set<NamePart>`).

- [ ] **Step 2: Write the test** — parametric cases: `"Abies alba", "Mill."`, rank species, code botanical → genus=Abies, specificEpithet=alba, combinationAuthorship=Mill.; `"Puma concolor", "(Linnaeus, 1771)"`, code zoological → basionymAuthorship=Linnaeus, basionymAuthorshipYear=1771 (bracketed authors are the basionym); a virus/placeholder string → parseState=UNPARSABLE, no throw; `formatName` round-trips a canonical string. Assert against `NameParserServiceTest` (a plain unit test — no DB needed).

Run: `cd backend && mvn -q -Dtest=NameParserServiceTest test` → PASS.

- [ ] **Step 3: Commit** — `feat(backend): GBIF name-parser 4.2.0 integration (parse + NameFormatter)`.

---

### Task 3: Reference CRUD + fuzzy search API

**Files:** `ReferenceService.java`, `ReferenceController.java`, `name/dto/*`, `ReferenceApiIT.java`.

**Interfaces / behavior:** `GET/POST /api/projects/{pid}/references`, `GET/PUT/DELETE /api/projects/{pid}/references/{id}`, `GET /api/projects/{pid}/references?q=<term>` (trigram fuzzy on `citation`, paginated). All gated by project membership (reuse Plan 1 `ProjectService.requireRole`/`requireVisible`); create/update/delete require editor+ (owner/editor). Sets `modified`/`modified_by`/bumps `version` (optimistic check on update). `q` search uses `citation % :q` (pg_trgm) ordered by `similarity(citation, :q) DESC`.

- [ ] TDD steps mirror Plan 1's ProjectApi tasks: DTOs → service (authz + optimistic version) → controller → `ReferenceApiIT` (create, list, search-by-term returns the matching ref, update bumps version, non-editor gets 403). Run `env 'api.version=1.41' mvn -q -Dtest=ReferenceApiIT test`. Commit.

---

### Task 4: NameUsage CRUD (auto-parse on write) + fuzzy search API

**Files:** `NameUsageService.java`, `NameUsageController.java`, `name/dto/*`, `NameUsageApiIT.java`.

**Interfaces / behavior:**
- `POST /api/projects/{pid}/usages` — body carries `scientificName`, `authorship`, `rank`, `status`, optional `parentId`, etc. The service calls `NameParserService.parseInto(usage, project.nomCode)` before insert, so atomized fields + `nameType`/`parseState` are populated. Returns the created usage plus a `formattedName` (from `NameFormatter`).
- `GET /api/projects/{pid}/usages/{id}` — the usage + its `formattedName`, its `synonym_accepted` links (accepted parents), and for an accepted usage its synonyms.
- `PUT /api/projects/{pid}/usages/{id}` — re-parses on scientificName/authorship/rank change; optimistic `version` check.
- `DELETE …/usages/{id}`.
- `GET /api/projects/{pid}/usages?q=<term>` — trigram fuzzy on `scientific_name`, paginated, ordered by similarity.
- `PUT …/usages/{id}/synonym-of/{acceptedId}` / `DELETE` — manage `synonym_accepted` links (pro parte: multiple accepted allowed).
- Authz: editor+ for writes. Parent must be an accepted usage (soft: allowed but flagged later by validation — here just store).

- [ ] TDD steps: DTOs → service (parse-on-write, authz, version, synonym links) → controller → `NameUsageApiIT` (create "Abies alba Mill." asserts genus=Abies+formattedName; search "Abies" finds it; attach a synonym via synonym-of and GET the accepted usage shows it; pro parte: attach the same synonym to a second accepted taxon; non-editor 403). Run `env 'api.version=1.41' mvn -q -Dtest=NameUsageApiIT test`. Commit.

---

## Self-Review Notes

- **Spec coverage (phase-1 item: core References/NameUsages CRUD + search + parser):** schema+entities (Task 1), parser (Task 2), Reference API (Task 3), NameUsage API incl. synonymy/pro parte + search (Task 4). The collapsed model, `synonym_accepted`, strings-authoritative parsing, project scoping, and `nom_code`-driven parse/format all realized.
- **Deferred to later plans:** the classification tree endpoints + `ltree` (Plan 4); audit/locks (Plan 5); validation engine incl. the parse/consistency rules (Plan 6); supporting entities (type material, distributions, vernaculars) UX; the **frontend** editing UI for references/usages (a follow-on frontend plan); ColDP import/export (phase 2/3).
- **Parser contract** is pinned to design-spec Appendix A (name-level authorship only; `new NameParserImpl()`; 4-arg `parse`).
- **Manual verification:** with a running backend + Postgres, `POST /api/projects/{id}/usages {"scientificName":"Abies alba","authorship":"Mill.","rank":"species","status":"accepted"}` returns atomized genus/epithet + a formatted name; `?q=abies` fuzzy-matches.
