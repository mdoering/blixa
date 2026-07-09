# P1 — Taxon-info refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move `extinct` / `environment` / `temporal_range_start` / `temporal_range_end` off the `name_usage` table into a dedicated `taxon_info` table keyed by `(project_id, usage_id)`, populated only for **accepted** usages, with an unchanged API wire shape.

**Architecture:** New `taxon_info` table (compound PK + CASCADE FK to `name_usage`). A `TaxonInfoMapper` upserts/deletes it. `NameUsageMapper`'s selects `LEFT JOIN` it so the `NameUsage` POJO/response keep the four fields unchanged; its `insert`/`update` no longer touch them. `NameUsageService` writes taxon info after each name-usage write — upsert when accepted-with-data, else delete.

**Tech Stack:** Java 25, Spring Boot 4.1, MyBatis (annotation mappers), Flyway, Postgres 17, Testcontainers ITs.

## Global Constraints

- Build/test with **JDK 25**: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/25.0.1-librca mvn ...` (default java 21 won't compile). Authoritative check: `mvn verify`.
- Flyway migrations are **forward-only**; this adds **`V9__taxon_info.sql`** (next after `V8`).
- The API request/response shape is **unchanged** — `CreateNameUsageRequest`, `UpdateNameUsageRequest`, `NameUsageResponse`, and the `NameUsage` POJO keep `extinct`/`environment`/`temporalRangeStart`/`temporalRangeEnd`. **No frontend changes.**
- `environment` is a `List<life.catalogue.api.vocab.Environment>` bound through the existing `org.catalogueoflife.editor.name.EnvironmentArrayTypeHandler`.
- `Status` is `org.catalogueoflife.editor.name.Status` (same package as `NameUsageService`/mappers — use unqualified `Status.ACCEPTED`).
- Invariant: `taxon_info` rows exist **only** for accepted usages that carry at least one value. Non-accepted or all-null → no row.
- Run backend commands from `backend/`.

---

### Task 1: `taxon_info` table + mapper + wire-in, with tests

**Files:**
- Create: `backend/src/main/resources/db/migration/V9__taxon_info.sql`
- Create: `backend/src/main/java/org/catalogueoflife/editor/name/TaxonInfoMapper.java`
- Modify: `backend/src/main/java/org/catalogueoflife/editor/name/NameUsageMapper.java`
- Modify: `backend/src/main/java/org/catalogueoflife/editor/name/NameUsageService.java`
- Create: `backend/src/test/java/org/catalogueoflife/editor/name/TaxonInfoMapperIT.java`
- Modify: `backend/src/test/java/org/catalogueoflife/editor/name/NameUsageApiIT.java`

**Interfaces:**
- Produces: `TaxonInfoMapper.upsert(int projectId, int usageId, Boolean extinct, List<Environment> environment, String temporalRangeStart, String temporalRangeEnd)`, `TaxonInfoMapper.delete(int projectId, int usageId)`, `TaxonInfoMapper.count(int projectId, int usageId)`.

- [ ] **Step 1: Write the failing behavior test** — add to `NameUsageApiIT.java`

Add this new test method (uses the existing `ensureUser`/`createProject` helpers + `json`/`mvc`). It asserts the accepted-only + clear-on-status-change behavior, which the current code does NOT satisfy (today `environment` is a plain `name_usage` column kept regardless of status).

```java
  @Test
  @org.springframework.security.test.context.support.WithMockUser(username = "tiUser")
  void taxonInfoIsAcceptedOnlyAndClearedOnStatusChange() throws Exception {
    ensureUser("tiUser");
    long pid = createProject("taxoninfoproj");

    // A SYNONYM created with environment keeps no taxon info (accepted-only).
    String synBody = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Syn env\",\"rank\":\"species\",\"status\":\"synonym\","
                + "\"environment\":[\"marine\"]}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    long synId = json.readTree(synBody).get("id").asLong();
    mvc.perform(get("/api/projects/" + pid + "/usages/" + synId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.environment[0]").doesNotExist());

    // An ACCEPTED usage with environment round-trips (via the taxon_info join)...
    String accBody = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Acc env\",\"rank\":\"species\",\"status\":\"accepted\","
                + "\"environment\":[\"marine\"]}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.environment[0]").value("MARINE"))
        .andReturn().getResponse().getContentAsString();
    long accId = json.readTree(accBody).get("id").asLong();
    int version = json.readTree(accBody).get("version").asInt();
    mvc.perform(get("/api/projects/" + pid + "/usages/" + accId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.environment[0]").value("MARINE"));

    // ...but demoting it to SYNONYM (full-replace update carrying environment, as the UI does)
    // sheds the taxon info.
    mvc.perform(put("/api/projects/" + pid + "/usages/" + accId).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Acc env\",\"rank\":\"species\",\"status\":\"synonym\","
                + "\"environment\":[\"marine\"],\"version\":" + version + "}"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/projects/" + pid + "/usages/" + accId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.environment[0]").doesNotExist());
  }
```

- [ ] **Step 2: Run it, verify it fails**

Run: `JAVA_HOME=~/.sdkman/candidates/java/25.0.1-librca mvn -Dtest=NameUsageApiIT test`
Expected: FAIL on `taxonInfoIsAcceptedOnlyAndClearedOnStatusChange` — the synonym's `environment[0]` is `"MARINE"` (currently stored on `name_usage` regardless of status), so `doesNotExist()` fails.

- [ ] **Step 3: Create the migration** — `backend/src/main/resources/db/migration/V9__taxon_info.sql`

```sql
-- Taxon-level attributes (extinct, environment, temporal range) apply only to ACCEPTED usages, so
-- they live in their own table keyed by the usage rather than on the shared name_usage row. This
-- lets a status change (accepted <-> synonym) re-key or drop one row instead of shuffling columns,
-- and keeps name_usage focused on name/nomenclatural fields.
-- Spec: docs/superpowers/specs/2026-07-09-taxon-info-refactor-design.md
CREATE TABLE taxon_info (
  project_id           INTEGER NOT NULL,
  usage_id             INTEGER NOT NULL,
  extinct              BOOLEAN,
  environment          TEXT[],            -- life.catalogue.api.vocab.Environment enum names
  temporal_range_start TEXT,
  temporal_range_end   TEXT,
  PRIMARY KEY (project_id, usage_id),
  FOREIGN KEY (project_id, usage_id)
      REFERENCES name_usage (project_id, id) ON DELETE CASCADE
);

-- Preserve existing taxon info for ACCEPTED usages only (the invariant going forward). In practice
-- there is none yet, so this copies nothing; kept for correctness.
INSERT INTO taxon_info (project_id, usage_id, extinct, environment,
                        temporal_range_start, temporal_range_end)
SELECT project_id, id, extinct, environment, temporal_range_start, temporal_range_end
FROM name_usage
WHERE status = 'ACCEPTED'
  AND (extinct IS NOT NULL OR environment IS NOT NULL
       OR temporal_range_start IS NOT NULL OR temporal_range_end IS NOT NULL);

ALTER TABLE name_usage
  DROP COLUMN extinct,
  DROP COLUMN environment,
  DROP COLUMN temporal_range_start,
  DROP COLUMN temporal_range_end;
```

- [ ] **Step 4: Create `TaxonInfoMapper`** — `backend/src/main/java/org/catalogueoflife/editor/name/TaxonInfoMapper.java`

```java
package org.catalogueoflife.editor.name;

import java.util.List;
import life.catalogue.api.vocab.Environment;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

// Taxon-level attributes for a single (accepted) usage. Reads happen through NameUsageMapper's
// LEFT JOIN so the NameUsage POJO/response are populated in one query; this mapper owns only the
// write side (upsert/delete) plus a count used by tests and future callers (e.g. P2 demote).
@Mapper
public interface TaxonInfoMapper {

  @Insert("""
      INSERT INTO taxon_info (project_id, usage_id, extinct, environment,
          temporal_range_start, temporal_range_end)
      VALUES (#{projectId}, #{usageId}, #{extinct},
          #{environment,typeHandler=org.catalogueoflife.editor.name.EnvironmentArrayTypeHandler},
          #{temporalRangeStart}, #{temporalRangeEnd})
      ON CONFLICT (project_id, usage_id) DO UPDATE SET
          extinct = EXCLUDED.extinct,
          environment = EXCLUDED.environment,
          temporal_range_start = EXCLUDED.temporal_range_start,
          temporal_range_end = EXCLUDED.temporal_range_end
      """)
  void upsert(@Param("projectId") int projectId, @Param("usageId") int usageId,
      @Param("extinct") Boolean extinct, @Param("environment") List<Environment> environment,
      @Param("temporalRangeStart") String temporalRangeStart,
      @Param("temporalRangeEnd") String temporalRangeEnd);

  @Delete("DELETE FROM taxon_info WHERE project_id = #{projectId} AND usage_id = #{usageId}")
  void delete(@Param("projectId") int projectId, @Param("usageId") int usageId);

  @Select("SELECT count(*) FROM taxon_info WHERE project_id = #{projectId} AND usage_id = #{usageId}")
  int count(@Param("projectId") int projectId, @Param("usageId") int usageId);
}
```

- [ ] **Step 5: Update `NameUsageMapper`** — `backend/src/main/java/org/catalogueoflife/editor/name/NameUsageMapper.java`

**(a)** In `insert`, remove `extinct, environment, temporal_range_start, temporal_range_end` from the column list and the matching four `VALUES` bindings (`#{extinct}`, the `#{environment,typeHandler=...}` line, `#{temporalRangeStart}`, `#{temporalRangeEnd}`). The column list becomes:

```
      INSERT INTO name_usage (
          project_id, id, coldp_id, alternative_id, parent_id, basionym_id, ordinal,
          status, name_phrase, reference_id,
          scientific_name, authorship, rank, uninomial, genus, infrageneric_epithet,
          specific_epithet, infraspecific_epithet, cultivar_epithet, notho,
          combination_authorship, combination_ex_authorship, combination_authorship_year,
          basionym_authorship, basionym_ex_authorship, basionym_authorship_year,
          sanctioning_author, nom_status, published_in_reference_id, published_in_year,
          published_in_page, published_in_page_link, gender, etymology, name_type,
          parse_state, link, remarks, modified_by)
      VALUES (
          #{projectId}, #{id}, #{coldpId},
          #{alternativeId,typeHandler=org.catalogueoflife.editor.name.StringArrayTypeHandler},
          #{parentId}, #{basionymId}, #{ordinal},
          #{status}, #{namePhrase},
          #{referenceId,typeHandler=org.catalogueoflife.editor.name.IntegerArrayTypeHandler},
          #{scientificName}, #{authorship}, #{rank}, #{uninomial}, #{genus}, #{infragenericEpithet},
          #{specificEpithet}, #{infraspecificEpithet}, #{cultivarEpithet}, #{notho},
          #{combinationAuthorship}, #{combinationExAuthorship}, #{combinationAuthorshipYear},
          #{basionymAuthorship}, #{basionymExAuthorship}, #{basionymAuthorshipYear},
          #{sanctioningAuthor}, #{nomStatus}, #{publishedInReferenceId}, #{publishedInYear},
          #{publishedInPage}, #{publishedInPageLink}, #{gender}, #{etymology}, #{nameType},
          #{parseState}, #{link}, #{remarks}, #{modifiedBy})
```

**(b)** In `update`, remove the three lines
`extinct = #{extinct},` / `environment = #{environment,typeHandler=...},` / `temporal_range_start = #{temporalRangeStart}, temporal_range_end = #{temporalRangeEnd},`
so the `SET` list goes straight from `reference_id = #{referenceId,...}` to `scientific_name = #{scientificName}, ...`.

**(c)** Replace the `findByIdInProject` `@Select` (keep its `@Results`/`@Param`/signature exactly) with the join:

```java
  @Select("""
      SELECT nu.*, ti.extinct, ti.environment,
             ti.temporal_range_start, ti.temporal_range_end
      FROM name_usage nu
      LEFT JOIN taxon_info ti ON ti.project_id = nu.project_id AND ti.usage_id = nu.id
      WHERE nu.project_id = #{projectId} AND nu.id = #{id}
      """)
```

**(d)** Replace the `searchItems` `@Select` body (keep `@ResultMap("nameUsageResult")`/signature) with the join + `nu.`-qualified columns:

```java
  @Select("""
      <script>
      SELECT nu.*, ti.extinct, ti.environment,
             ti.temporal_range_start, ti.temporal_range_end
      FROM name_usage nu
      LEFT JOIN taxon_info ti ON ti.project_id = nu.project_id AND ti.usage_id = nu.id
      WHERE nu.project_id = #{projectId}
      <if test="q != null">AND nu.scientific_name % #{q}</if>
      <if test="rank != null">AND nu.rank = #{rank}</if>
      <if test="status != null">AND nu.status = #{status}</if>
      <choose>
        <when test="q != null">ORDER BY similarity(nu.scientific_name, #{q}) DESC</when>
        <otherwise>ORDER BY nu.scientific_name</otherwise>
      </choose>
      LIMIT #{limit} OFFSET #{offset}
      </script>
      """)
```

Leave `countMatches`, `delete`, `findIdsByProject`, `countDuplicates`, `findIdsByPublishedInReference` unchanged (none select taxon-info columns).

- [ ] **Step 6: Wire `TaxonInfoMapper` into `NameUsageService`** — `NameUsageService.java`

Add the field, constructor param, assignment, and the helper; call it in `create` and `update`.

Field (next to the other `private final ... Mapper` fields):
```java
  private final TaxonInfoMapper taxonInfo;
```
Constructor: add `TaxonInfoMapper taxonInfo` to the parameter list and, in the body, `this.taxonInfo = taxonInfo;`.

Helper (place near the other private helpers, e.g. below `parseEnvironments`):
```java
  // Taxon-level attributes (extinct/environment/temporal range) live in taxon_info and belong only
  // to accepted usages. Upsert them when the usage is accepted and carries a value; otherwise
  // (non-accepted, or accepted with nothing set) ensure no row lingers. Runs inside the caller's
  // write transaction. Spec: docs/superpowers/specs/2026-07-09-taxon-info-refactor-design.md
  private void writeTaxonInfo(NameUsage u) {
    boolean hasData = u.getExtinct() != null || u.getEnvironment() != null
        || u.getTemporalRangeStart() != null || u.getTemporalRangeEnd() != null;
    if (u.getStatus() == Status.ACCEPTED && hasData) {
      taxonInfo.upsert(u.getProjectId(), u.getId(), u.getExtinct(), u.getEnvironment(),
          u.getTemporalRangeStart(), u.getTemporalRangeEnd());
    } else {
      taxonInfo.delete(u.getProjectId(), u.getId());
    }
  }
```

In `create`, immediately after `usages.insert(u);`:
```java
    usages.insert(u);
    writeTaxonInfo(u);
```

In `update`, immediately after the stale-version guard and before the `after` re-fetch:
```java
    int updated = usages.update(u);
    if (updated == 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "conflict: stale version");
    }
    writeTaxonInfo(u);
    NameUsage after = requireInProject(projectId, id);
```

- [ ] **Step 7: Run the behavior test, verify it now passes**

Run: `JAVA_HOME=~/.sdkman/candidates/java/25.0.1-librca mvn -Dtest=NameUsageApiIT test`
Expected: PASS (both `crudSearchSynonymyAndAuthz` and `taxonInfoIsAcceptedOnlyAndClearedOnStatusChange`).

- [ ] **Step 8: Strengthen the existing round-trip assertion** — `NameUsageApiIT.java`

In `crudSearchSynonymyAndAuthz`, the existing `GET /usages/{accId}` (the one asserting `$.synonymIds[0]`) currently never re-reads the taxon info. Add two assertions to that same `get(...)` chain to prove the join read:

```java
    mvc.perform(get("/api/projects/" + pid + "/usages/" + accId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.synonymIds[0]").value(synId))
        .andExpect(jsonPath("$.environment[0]").value("TERRESTRIAL"))
        .andExpect(jsonPath("$.temporalRangeStart").value("Holocene"));
```

- [ ] **Step 9: Add the mapper cascade test** — `backend/src/test/java/org/catalogueoflife/editor/name/TaxonInfoMapperIT.java`

```java
package org.catalogueoflife.editor.name;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import life.catalogue.api.vocab.Environment;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectMapper;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TaxonInfoMapperIT extends AbstractPostgresIT {

  private static final String ENTITY = "name_usage";

  @Autowired ProjectMapper projects;
  @Autowired AppUserMapper users;
  @Autowired NameUsageMapper nameUsages;
  @Autowired TaxonInfoMapper taxonInfo;
  @Autowired IdSeqMapper idSeq;

  @Test
  void upsertOverwriteDeleteAndCascade() {
    AppUser user = new AppUser();
    user.setUsername("ti-mapper-editor");
    users.insert(user);
    Project p = new Project();
    p.setTitle("TIMapper");
    projects.insert(p);
    int projectId = p.getId();

    NameUsage u = new NameUsage();
    u.setProjectId(projectId);
    u.setId(idSeq.allocate(projectId, ENTITY));
    u.setStatus(Status.ACCEPTED);
    u.setScientificName("Abies alba");
    u.setRank("species");
    u.setModifiedBy(user.getId());
    nameUsages.insert(u);

    // upsert + overwrite (same PK) then delete
    taxonInfo.upsert(projectId, u.getId(), true, List.of(Environment.MARINE), "Jurassic", null);
    assertThat(taxonInfo.count(projectId, u.getId())).isEqualTo(1);
    taxonInfo.upsert(projectId, u.getId(), false, List.of(Environment.TERRESTRIAL), null, "Holocene");
    assertThat(taxonInfo.count(projectId, u.getId())).isEqualTo(1);
    taxonInfo.delete(projectId, u.getId());
    assertThat(taxonInfo.count(projectId, u.getId())).isEqualTo(0);

    // ON DELETE CASCADE: deleting the usage removes any taxon_info row
    taxonInfo.upsert(projectId, u.getId(), true, null, null, null);
    assertThat(taxonInfo.count(projectId, u.getId())).isEqualTo(1);
    assertThat(nameUsages.delete(projectId, u.getId())).isEqualTo(1);
    assertThat(taxonInfo.count(projectId, u.getId())).isEqualTo(0);
  }
}
```

- [ ] **Step 10: Run the full suite**

Run: `JAVA_HOME=~/.sdkman/candidates/java/25.0.1-librca mvn verify`
Expected: BUILD SUCCESS — all unit tests + all ITs green (existing name-usage/tree/validation ITs unaffected by the unchanged wire shape; the two new tests + strengthened assertions pass).

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/resources/db/migration/V9__taxon_info.sql \
        backend/src/main/java/org/catalogueoflife/editor/name/TaxonInfoMapper.java \
        backend/src/main/java/org/catalogueoflife/editor/name/NameUsageMapper.java \
        backend/src/main/java/org/catalogueoflife/editor/name/NameUsageService.java \
        backend/src/test/java/org/catalogueoflife/editor/name/TaxonInfoMapperIT.java \
        backend/src/test/java/org/catalogueoflife/editor/name/NameUsageApiIT.java
git commit -m "refactor(backend): move taxon info (extinct/env/temporal) to taxon_info table"
```

---

### Task 2: Dev-boot verification + wrap

**Files:** none (verification + docs).

- [ ] **Step 1: Confirm Flyway applies V9 on the dev DB and a usage still round-trips**

Ensure Postgres is up (`docker compose up -d` from repo root), then boot the dev backend:
```bash
cd backend && JAVA_HOME=~/.sdkman/candidates/java/25.0.1-librca mvn spring-boot:run -Dspring-boot.run.profiles=dev
```
Confirm the log shows Flyway migrating to version 9 and `Started EditorApplication` with no error. Then, in another shell, verify the schema + a round-trip:
```bash
docker exec coldp-editor-db psql -U coldp_editor -d coldp_editor -tAc \
  "select count(*) from information_schema.tables where table_name='taxon_info'"   # -> 1
docker exec coldp-editor-db psql -U coldp_editor -d coldp_editor -tAc \
  "select column_name from information_schema.columns where table_name='name_usage' and column_name in ('extinct','environment','temporal_range_start','temporal_range_end')"  # -> (empty: columns gone)
```
(The seeded Felidae usages have no taxon info, so nothing was copied — expected.) Stop the dev server (`pkill -f spring-boot:run`).

- [ ] **Step 2: Update the ledger + todo**

Append a P1-done entry to `.superpowers/sdd/progress.md` and note in `todo.md` that P1 (taxon-info refactor) is done and P2 (acc↔syn workflow) is next. Commit `todo.md`:
```bash
git add todo.md && git commit -m "docs(todo): P1 taxon-info refactor done; P2 acc<->syn workflow next"
```

---

## Self-Review

**Spec coverage:** V9 table + copy + drop (Step 3) ✓; `TaxonInfoMapper` upsert/delete (Step 4) ✓; `NameUsageMapper` join + trimmed insert/update, `countMatches` untouched (Step 5) ✓; `NameUsageService.writeTaxonInfo` after create/update with the audit-`after` ordering (Step 6) ✓; accepted-only + silent-drop + cascade (Steps 1/9) ✓; unchanged wire shape / no frontend (Global Constraints) ✓; tests: join round-trip (Step 8), accepted-only + clear-on-demote (Step 1), cascade (Step 9) ✓; dev-boot Flyway verification (Task 2) ✓.

**Placeholder scan:** none — every step has full SQL/Java/commands.

**Type consistency:** `TaxonInfoMapper.upsert(projectId, usageId, Boolean extinct, List<Environment> environment, String, String)` is called with exactly those types from `writeTaxonInfo` (`u.getExtinct()` Boolean, `u.getEnvironment()` `List<Environment>`, temporal Strings). `count(int,int)` returns `int`, asserted against ints in the IT. `Status.ACCEPTED` used unqualified (same package). `EnvironmentArrayTypeHandler` FQN matches the existing handler used in `NameUsageMapper`.

**Ordering note (verified against the spec):** `writeTaxonInfo(u)` in `update` runs *after* the CAS success and *before* `requireInProject` re-fetch, so the audit `before`/`after` diff still reflects taxon-info edits.
