# Direct CLB taxon import — design

**Status:** approved (brainstorm) · **Date:** 2026-07-11

## Context & goal

A lightweight, **synchronous** "pull straight in" tool: from a project taxon, fetch a chosen taxon
(a genus subtree, or a single species with all its info) from a ChecklistBank dataset via CLB's JSON
API and **insert it directly** into the project. Unlike the ColDP import + supervised merge, this is
for **pointy** imports of single names or small groups — no async export/download, no staging project,
no name-matching, no merge review, **no transaction**. References may duplicate against the target;
that's acceptable — a future reference-dedup tool reconciles them.

## Non-goals

- **No name-matching / merge / reconciliation.** A re-pull duplicates (the supervised-merge + ColDP
  route is the reconciling path). This tool is deliberately "just add it".
- No reference deduplication (refs are inserted as-is; a later dedup/merge tool handles it).
- No async job / progress polling — it's synchronous and small (with a size cap).
- No bulk/whole-dataset import (that's the ColDP import).

## Entry point & selection

- **Tree context-menu** (⋮ / right-click) on an **accepted** project taxon → **"Import from
  ChecklistBank"**. That taxon is the **focal** taxon.
- The modal selects a CLB **source** two ways:
  1. **Paste a CLB taxon URL** — parse `checklistbank.org/dataset/{key}/taxon/{id}` (→ datasetKey +
     taxonId) or the COL portal `catalogueoflife.org/data/taxon/{id}` (→ COL release `3LXR` + taxonId).
     Lenient parse; on success it resolves the taxon (a `GET …/taxon/{id}` to confirm the name) and
     skips the pickers.
  2. **Suggest pickers** — a **dataset** autocomplete (`GET /dataset?q=`) then a **taxon** autocomplete
     within it (`GET /dataset/{key}/nameusage?q=&rank=`), with an optional **rank filter**.

## Import modes

After a source taxon is chosen, one of three modes:

- **A · Taxon + subtree → new children.** The selected taxon and its descendants are inserted as new
  children under the focal taxon (accepted tree; synonyms linked).
- **B · Children only.** The selected taxon's children (and their subtrees) become new children of the
  focal; the selected taxon itself is skipped.
- **C · Update the focal taxon.** Import the selected taxon's **synonymy** + chosen **supplementary
  infos** *onto the focal taxon* (no new accepted children). The user picks which entity types via
  checkboxes: **Synonyms · Vernacular names · Distributions · Type material · Media · Estimates ·
  Properties · Name relations**. (Modes A/B pull the full set for every imported usage.)

## CLB API (public reads, no auth)

Grounded in the CLB webservice (`life.catalogue.resources.dataset.*`):
- **Dataset suggest:** `GET /dataset?q=` → `ResultPage<Dataset>`.
- **Taxon suggest:** `GET /dataset/{key}/nameusage?q=&rank=` → `ResultPage<NameUsageBase>` (rank filter).
- **The bundle:** `GET /dataset/{key}/taxon/{id}/info` → `UsageInfo` — the usage + name + **synonyms,
  vernacular names, distributions, media, type material, references, estimates, properties, name
  relations** in one call. This maps 1:1 to our entities (the same set the extended-ColDP import
  handles).
- **Children (recursion for A/B):** the tree-children endpoint (`GET /dataset/{key}/tree/{id}/children`
  or the nameusage list filtered by parent — resolve the exact call in the plan).

**Reuse the CLB `api` model:** CLB JSON deserializes directly into `life.catalogue.api.model.*`
(`UsageInfo`, `Taxon`, `Synonym`, `Name`, `VernacularName`, `Distribution`, `Reference`, …) — already
on the classpath via the `reader`→`api` dependency (Jackson 2) — so there is **no hand-rolled JSON
mapping**, only api-model → our-model (the inverse of the same field mapping the ColDP export/import
already encodes).

## Backend

- **`ClbImportClient`** — a CLB read client (reuses the existing `RestClient` + `coldp.clb.base-url`
  config): `searchDatasets(q)`, `searchUsages(datasetKey, q, rank)`, `usageInfo(datasetKey, id)`,
  `children(datasetKey, id)`. Deserializes into the CLB api model (a Jackson-2 mapper for those
  classes). Read-only; public datasets need no auth.
- **`ClbImportService.importFromClb(userId, projectId, focalUsageId, datasetKey, sourceTaxonId, mode,
  Set<EntityType> entityTypes)`** — owner/editor on the project; focal must be an **accepted** usage.
  - Gather the usages to insert per mode (A: source + subtree; B: source's children + subtrees;
    C: none — attach to focal). Enforce a **size cap** (`coldp.clb-import.max-usages`, default e.g.
    500) — over-cap → 400 "pick a smaller root".
  - For each gathered usage: map its `UsageInfo` → our create path — insert the accepted name (parent
    = focal for a mode-A/B root, else its already-inserted parent), its synonyms (`synonym_accepted`
    links), and the chosen child entities (accepted-only guard for the 5 taxon-scoped). References are
    inserted (deduped **within this pull** by CLB ref id; may duplicate existing target refs — allowed).
    Carry the CLB source id as a provenance CURIE: `col:<id>` when the dataset is the COL release, else
    `<datasetKey>:<id>`.
  - **Mode C:** attach the selected taxon's synonyms + chosen infos to the **focal** usage (no accepted
    children); respects the entity-type selection.
  - **No transaction** — inserts commit as they go (small, additive; a partial failure leaves what was
    inserted + a clear error). Reuse `NameUsageService`/the raw mappers, the child mappers,
    `ReferenceMapper`, `IdSeqMapper`, `SynonymAcceptedMapper`, `TaxonInfoMapper`.
  - Returns a summary: counts (name-usages, synonyms, references, per child type) + any per-record issues.
- **Endpoint:** `POST /api/projects/{pid}/usages/{focalId}/clb-import`
  (body `{datasetKey, sourceTaxonId, mode, entityTypes}`) → 200 summary. (Plus, optionally, thin
  proxy endpoints for dataset/taxon suggest if we don't call CLB directly from the browser — see
  Frontend.)

## Frontend

- **Context-menu entry** "Import from ChecklistBank" on accepted taxa in the tree (mirror the existing
  ⋮/right-click actions) → **`ClbImportModal`** (focal = the taxon).
- The modal: a **URL paste** field (parsed → dataset+taxon, with the resolved name shown) OR the
  **dataset** + **taxon** autocompletes (rank filter) → a **mode** radio → (mode C) the **entity-type
  checkboxes** → **Import** → a summary + refresh the tree/detail (invalidate the focal's children +
  detail queries).
- Suggest calls: either the browser hits CLB directly (public API, CORS permitting) or via thin backend
  proxy endpoints (`GET /api/clb/datasets?q=`, `GET /api/clb/{key}/usages?q=&rank=`) — **prefer the
  proxy** (avoids CORS + keeps the CLB base-url server-configured). Decide in the plan.

## Testing

- `ClbImportClient` against a mocked CLB (WireMock / `@MockitoBean` — mirror `ClbMatchClient`'s test):
  URL-parse cases, dataset/usage suggest, `usageInfo` deserialization into the api model.
- `ClbImportService` IT (mock the client): mode A (source+subtree inserted under focal, synonyms +
  child entities + refs created, provenance CURIEs); mode B (children only, source skipped); mode C
  (focal gains the synonyms + only the checked entity types; no new accepted children); the size cap;
  accepted-only child guard.
- Frontend: the modal — URL parse fills dataset+taxon; pickers; mode C checkboxes; import → summary +
  tree refresh (MSW).

## Build phases

1. **`ClbImportClient` + mapping** — the CLB read client + api-model→our-model mapping (unit/mock).
2. **`ClbImportService` + endpoint** — the three modes, size cap, provenance, no-transaction insert; IT.
3. **Frontend** — context-menu entry + `ClbImportModal` (URL paste + suggest pickers + modes + entity
   picker) + suggest proxy endpoints.

## Caveats / future

- **Duplication on re-pull** is by design (no matching); the reconciling path is ColDP import +
  supervised merge. A future **reference-dedup tool** (see `features.md#tools`) cleans up duplicated refs.
- Size cap keeps it "pointy"; a large subtree should go through the ColDP-export→import→merge route.
- CLB reads assume public datasets (no auth); a private dataset would need a token — out of scope now.
- Provenance CURIE scope for non-COL datasets is the bare `datasetKey`; a nicer scheme can come with
  the configured-identifier-scopes work.
