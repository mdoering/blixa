# Bulk-match all configured identifier scopes — design

**Status:** approved (brainstorm) · **Date:** 2026-07-10

## Context & goal

The bulk "Match all to COL" job matches every name in a project against the Catalogue of Life
(`3LXR`) and stores `col:<id>` in `alternative_id`. Now that projects configure a set of **identifier
scopes** (for the per-scope taxon-form fields), the bulk job should match against **all configured
matchable scopes**, not just COL — e.g. IPNI for a botanical project.

Matching a scope requires a **CLB dataset key** whose usage ids ARE that scope's identifiers (as COL's
`3LXR` release usage ids are `col:` ids). Most scopes lack a known key, so the scope config must carry
an optional dataset key; a scope **with** a key is matchable, one **without** is a manual-entry
form-field-only scope. **COL is special:** it is a CLB *project* that generates releases with changing
dataset keys, so it uses the `3LXR` magic alias (latest release), not a fixed key.

## Non-goals
- The single-taxon "Match to COL" modal and the GBIF map layer stay **COL-specific** (the map keys off
  `col:` ids). Only the **bulk** job generalizes.
- No automatic dataset-key discovery — the user supplies each scope's CLB dataset key (COL prefilled `3LXR`).

## Config: scope → dataset key as JSONB

- **V17 migration:** change `project.identifier_scopes` from `TEXT[]` to **`JSONB`**, a JSON array of
  `{"scope": "...", "datasetKey": "..."}` (datasetKey optional). Convert existing data:
  `ALTER TABLE project ALTER COLUMN identifier_scopes TYPE jsonb USING (
     CASE WHEN identifier_scopes IS NULL THEN NULL
     ELSE (SELECT jsonb_agg(jsonb_build_object('scope', s)) FROM unnest(identifier_scopes) s) END);`
- **Backend model:** `record IdentifierScope(String scope, String datasetKey)`; `Project.identifierScopes`
  becomes `List<IdentifierScope>`. A MyBatis **JSONB `TypeHandler`** (Jackson 3 `tools.jackson`
  serialize/deserialize `List<IdentifierScope>` ↔ a `PGobject` of type `jsonb`) — used in the Project
  SELECT/UPDATE (replaces the `StringArrayTypeHandler` for this column).
- **DTOs:** `ProjectResponse.identifierScopes: List<IdentifierScope>` and
  `UpdateProjectMetadataRequest.identifierScopes: List<IdentifierScope>` (records; arity unchanged — the
  component type changes from `List<String>` to `List<IdentifierScope>`).
- **Frontend:** `Project.identifierScopes: { scope: string; datasetKey?: string | null }[]`. The Project
  settings UI becomes an editable list of rows — each: a scope (creatable select from `/api/coldp/id-scopes`
  + custom) + an optional **dataset key** text input; a **COL** scope prefills `datasetKey = 3LXR` (with a
  hint that COL is a CLB project alias). Include the list in the metadata update payload.
- **Per-scope taxon-form fields** (existing): update `TaxonDetail` to read `project.identifierScopes.map(s
  => s.scope)` (the field set is unchanged; only the shape of the source changed from strings to objects).

## The bulk job — generalize to all matchable scopes

Generalize `ColMatchJobService` (the `col_match_run` async job) from COL-only to per-scope:

- **`ClbMatchClient.match`** gains a `datasetKey` parameter (currently it bakes in the `coldp.col.match-dataset`
  config). The single-taxon `ColMatchService` passes the config `3LXR`; the bulk job passes each scope's
  `datasetKey`.
- **Per usage × matchable scope:** call `clb.match(scope.datasetKey, sciName, authorship, rank, code,
  classification)` → the matched usage id in that dataset = the scope's identifier → reconcile
  `<scope>:<id>` into `alternative_id` with the SAME 4-branch logic as today (VERIFIED / ADDED / UPDATED /
  UNMATCHED), preserving other scopes' CURIEs and the review status of unchanged flags.
- **Per-scope flags:** the issue `rule` keys become scope-qualified — `<scope>_id_added`,
  `<scope>_id_updated`, `<scope>_id_missing` (so a usage can be `col_id_missing` AND `ipni_id_added`
  simultaneously, respecting `UNIQUE(project_id,entity_type,entity_id,rule)`). The delete-before-insert
  clears only THIS scope's three keys before re-matching (generalize `deleteColFlags`/`findColFlags` to take
  the scope). The reconcile-preservation (keep a reviewed flag when the same rule recurs) is unchanged, now
  per scope. The validation reconcile already leaves non-ValidationRule keys alone, so the dynamic
  `<scope>_id_*` keys survive per-usage revalidation.
- **The run:** `col_match_run` counters (total/processed/verified/added/updated/unmatched) become aggregate
  across all scopes × usages (a usage matched against 3 scopes = 3 processed increments). `total` =
  usages × matchable-scopes. `matchOne` becomes `matchOneScope(projectId, usageId, scope, userId)`; the loop
  iterates usages, then that usage's matchable scopes. If the project has **no** matchable scopes → the run
  completes immediately (total 0).
- COL runs as one configured scope (`col` / `3LXR`) — projects that want COL matching add it to their
  scopes (a botanical project might have `ipni` + `col`).

## Frontend
- The Project-page **"Match all to COL"** action becomes **"Match all identifiers"** — same run start/poll/
  summary UI (`col_match_run`), now summarizing across scopes. Disabled (or a hint) when the project has no
  matchable scopes.
- Flags surface in the Issues dashboard/per-usage as today (now scope-qualified rule keys).

## Testing
- Migration: existing `TEXT[]` scopes convert to `[{scope}]`; a `{scope,datasetKey}` round-trips through the
  JSONB TypeHandler (Project IT).
- `ClbMatchClient.match(datasetKey, …)` passes the dataset key through (the URL uses it) — the single-taxon
  path still uses `3LXR`.
- Bulk job IT (`@MockitoBean ClbMatchClient`): a project with two matchable scopes (`col`/`3LXR`, `ipni`/a
  key) + a manual-only scope (no key); the job matches each usage against col+ipni (NOT the keyless scope),
  stores `col:` + `ipni:` CURIEs, records `<scope>_id_added`/`_missing` flags; counters aggregate; a re-run
  preserves a reviewed `ipni_id_missing`.
- Frontend: the Project settings scope+datasetKey editor round-trips; the taxon-form per-scope fields still
  render from the new object shape; the "Match all identifiers" summary renders.

## Build phases
1. **V17 + config shape** — `identifier_scopes` → JSONB, `IdentifierScope` model + JSONB TypeHandler,
   ProjectResponse/UpdateProjectMetadataRequest, Project settings UI (scope+datasetKey), update the taxon-form
   per-scope field source. (No matching yet.) Clean verify.
2. **Parameterize `ClbMatchClient.match(datasetKey, …)`** + the single-taxon `ColMatchService` passes `3LXR`.
3. **Generalize the bulk job** — `matchOneScope`, per-scope flags (`<scope>_id_*`), iterate matchable scopes,
   aggregate counters; per-scope flag clear/preserve. IT.
4. **Frontend** — "Match all identifiers" action + summary; no-matchable-scopes handling.

## Caveats
- Assumes a scope's CLB dataset uses the source's native ids as usage ids (so `usage.id` = the `<scope>:` id).
  True for COL (3LXR) and CLB-hosted source datasets that preserve source ids; a dataset that reassigned ids
  would store CLB-usage-ids, not the source identifier — document, user-configured risk.
- Matching N scopes × M usages = N×M CLB calls; the single-thread executor keeps it polite but a large
  project × many scopes is slow (acceptable; it's an on-demand job with progress).
