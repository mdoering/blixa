# P1 — Taxon-info refactor (extinct / environment / temporal → `taxon_info`)

**Date:** 2026-07-09
**Scope:** Backend only. Move the taxon-level attributes `extinct`, `environment`,
`temporal_range_start`, `temporal_range_end` off the `name_usage` table into a dedicated
`taxon_info` table keyed by `(project_id, usage_id)`, populated only for **accepted** usages. The API
wire shape (request/response) and the frontend are **unchanged**.

## Context — where this sits

This is **P1 of a 4-part "synonym management & accepted↔synonym workflow" effort** (decomposed during
brainstorming, each its own spec → plan → ship):

- **P1 (this spec)** — taxon-info refactor. Foundational: it makes status-change relinking clean.
- **P2** — acc↔syn workflow: atomic `demote`/`promote` endpoints + guided modals.
- **P3** — pro parte & interactive synonym links (unlink, "Add accepted name…"), pro-parte split on promote.
- **P4** — `genus_mismatch` validation rule.

The motivation for doing P1 first: `extinct`/`environment`/`temporal` are **taxon** attributes, not
**name** attributes ("only accepted names carry taxon information", `features.md`). Storing them on
the shared `name_usage` row means a demoted synonym would keep taxon info it must not have, and a
status change would have to copy columns around. A separate table lets demote/promote just **re-key
or drop** one row.

## Current state (verified)

- `name_usage` (V3) has columns `extinct BOOLEAN`, `environment TEXT[]`, `temporal_range_start TEXT`,
  `temporal_range_end TEXT`.
- `NameUsageMapper` is annotation-based. `insert`/`update` list those 4 columns; `findByIdInProject`
  and `searchItems` use `SELECT *` with a `@Results` map that type-handles `environment` via
  `EnvironmentArrayTypeHandler`. `countMatches` does not select them.
- `NameUsageService.create`/`update` set the 4 fields on the `NameUsage` POJO from the request DTO,
  then `insert`/`update`.
- **Frontend:** `TaxonDetail` does **not** expose these as editable inputs — it only carries the
  loaded values back in the update payload (so a round-trip preserves them). No user-facing control.

## Design

### Table (V9 migration)

```sql
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

-- Preserve existing taxon info for ACCEPTED usages only (the invariant going forward).
-- In practice there is no such data yet, so this copies nothing; kept for correctness.
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

Note (dev): a persistent dev DB just picks up V9 on next boot (the seeded Felidae/Dracula rows have
no taxon info → nothing copied). Fresh Testcontainers apply V1–V9 in order.

### `TaxonInfoMapper` (new, annotation-based)

- `void upsert(int projectId, int usageId, Boolean extinct, List<Environment> environment, String temporalRangeStart, String temporalRangeEnd)`
  → `INSERT INTO taxon_info (...) VALUES (...) ON CONFLICT (project_id, usage_id) DO UPDATE SET ...`,
  with `environment` bound through `EnvironmentArrayTypeHandler`.
- `void delete(int projectId, int usageId)` → `DELETE FROM taxon_info WHERE project_id=? AND usage_id=?`.

### `NameUsageMapper` changes

- **`insert`**: remove `extinct, environment, temporal_range_start, temporal_range_end` from the
  column list and the `VALUES` list.
- **`update`**: remove those four `SET` assignments.
- **`findByIdInProject`**: 
  `SELECT nu.*, ti.extinct, ti.environment, ti.temporal_range_start, ti.temporal_range_end
   FROM name_usage nu LEFT JOIN taxon_info ti ON ti.project_id = nu.project_id AND ti.usage_id = nu.id
   WHERE nu.project_id = #{projectId} AND nu.id = #{id}`. Keep the existing `@Results` map (the
  `environment` type-handler entry still applies; the other three auto-map underscore→camel).
- **`searchItems`**: same `LEFT JOIN`; qualify every existing column reference with the `nu.` alias
  (`nu.project_id`, `nu.scientific_name`, `nu.rank`, `nu.status`, and `ORDER BY nu.scientific_name`
  / `similarity(nu.scientific_name, #{q})`) to avoid ambiguity, and select the four `ti.` columns.
- **`countMatches`**: unchanged (it selects no taxon-info columns; `name_usage` alone).
- The `NameUsage` POJO keeps its `extinct`/`environment`/`temporalRangeStart`/`temporalRangeEnd`
  fields — now populated from the join rather than base columns. `NameUsageResponse.of` unchanged.

### `NameUsageService` changes

Add a private helper and call it inside the same transaction as the write:

```java
private void writeTaxonInfo(NameUsage u) {
  boolean hasData = u.getExtinct() != null || u.getEnvironment() != null
      || u.getTemporalRangeStart() != null || u.getTemporalRangeEnd() != null;
  if (u.getStatus() == Status.ACCEPTED && hasData) {
    taxonInfo.upsert(u.getProjectId(), u.getId(), u.getExtinct(), u.getEnvironment(),
        u.getTemporalRangeStart(), u.getTemporalRangeEnd());
  } else {
    // Non-accepted, or accepted-with-no-values: hold no taxon_info row.
    taxonInfo.delete(u.getProjectId(), u.getId());
  }
}
```

- **`create`**: call `writeTaxonInfo(u)` right after `usages.insert(u)` (before `u.setVersion(0)` /
  audit; `u` already carries the request values). On create the `delete` branch is a harmless no-op.
- **`update`**: call `writeTaxonInfo(u)` right after the successful `usages.update(u)` CAS (i.e. after
  the `updated == 0 → 409` guard) and **before** the `after = requireInProject(...)` re-fetch, so the
  audit `before`/`after` diff still reflects env/extinct/temporal changes.

### Enforcement of the accepted-only invariant

- Writing taxon info on a **non-accepted** usage is silently dropped (the `else` branch deletes any
  row) rather than 400 — the wire payload still carries the fields (the UI does today), and a status
  change to non-accepted must simply shed the taxon info. This keeps the existing naive "change
  status" path working and matches `features.md` ("only accepted names carry taxon information").
- `ON DELETE CASCADE` removes taxon info when a usage is deleted (no orphan cleanup needed, unlike
  the polymorphic `issue` rows).

## Out of scope

- No frontend changes (no editable taxon-info inputs exist yet).
- No demote/promote logic, no migration-to-another-accepted (P2).
- No new editable UI for these fields (future, with the other supporting entities).
- Generic `properties` storage was considered and rejected — a dedicated typed table keeps `extinct`
  boolean / `environment` enum[] / temporal range typed.

## Testing

Backend (`mvn verify`, JDK 25). Add/adjust:

- **`TaxonInfoMapperIT`** (or fold into an existing IT): upsert then read back via
  `NameUsageMapper.findByIdInProject` returns the environment array; a second upsert overwrites;
  `delete` removes it.
- **API round-trip (extend `NameUsageApiIT`):**
  - create an **accepted** usage with `environment` (e.g. `["marine"]`) + `extinct` → GET returns them
    (proves join + upsert path).
  - create a **synonym** usage with `environment` set → GET returns `environment = null` (accepted-only).
  - update an accepted usage that has taxon info to `status = SYNONYM` → GET returns the taxon-info
    fields cleared (the row was deleted).
- **Cascade:** deleting an accepted usage that has a `taxon_info` row leaves no orphan (assert via the
  mapper or a re-create).
- All existing name-usage / tree / validation ITs stay green — the request/response shape is
  unchanged, so they need no edits (verify; adjust only if a test asserted a base-column detail).

## Verification

- `mvn verify` green (unit + IT).
- Boot the `dev` profile against the compose DB: confirm Flyway applies V9, the app starts, and an
  existing seeded usage still round-trips through GET (`/api/projects/3/usages/{id}`) with the same
  shape. (No browser needed — backend-only change.)
