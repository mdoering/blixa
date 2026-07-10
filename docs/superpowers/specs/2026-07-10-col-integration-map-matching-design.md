# COL Integration: distribution/type-specimen map + COL name matching

**Status:** approved (brainstorm) · **Date:** 2026-07-10

## Context & goal

The editor already stores distributions and type material as child entities of a name
usage. This feature adds:

1. A **map** inside the Distribution tab that draws, for the open (focal) taxon and its
   descendants: curated **distribution polygons**, **type-specimen points** (lat/lon), and
   an optional, project-gated **GBIF occurrence** raster.
2. **COL name matching** — resolve a local name to its Catalogue of Life usage id and store
   it as a ColDP identifier (`col:<id>`). That COL id is exactly the GBIF map `taxonKey`
   (with `checklistKey` pinned to COL), so matching is what lights up the GBIF layer.
3. A **bulk, project-wide match** job that verifies existing COL ids, adds missing ones, and
   flags the outcomes as issues.

The COL id is the linchpin: portal-components' `DistributionsMap` renders GBIF density tiles
filtered by `checklistKey` (COL's backbone UUID, constant) + `taxonKey` (the COL usage id).
So no GBIF backbone key is needed — the COL usage id *is* the key.

## Non-goals (v1)

- No GBIF backbone `taxonKey` capture (COL id suffices for the layer).
- No editing of distribution geometry (polygons are read-only CLB vocab areas).
- No clustering/heatmap of type points beyond simple circle markers.
- No scheduled/automatic re-matching (bulk match is user-triggered).

## Storage & data-model changes

- **`name_usage.alternative_id TEXT[]`** already exists and is mapped on `NameUsage`
  (`List<String> alternativeId`). We store the COL id there as a ColDP CURIE `col:<usageId>`
  (e.g. `col:6W3C4`). No new column for identifiers.
- **V11 migration** adds:
  - `type_material.latitude DOUBLE PRECISION`, `type_material.longitude DOUBLE PRECISION`.
  - `project.gbif_occurrence_layer BOOLEAN NOT NULL DEFAULT true`.

## Config properties (all tunable; defaults shown)

- `coldp.clb.base-url` = `https://api.checklistbank.org`
- `coldp.col.match-dataset` = `3LXR`  (latest COL **extended** release alias; resolved server-side)
- `coldp.col.gbif-checklist-key` = `7ddf754f-d193-4cc9-b351-99906754a03b`  (COL backbone UUID)

Front-end constants (not secret, may hardcode with a comment): GBIF api base
`https://api.gbif.org`, CARTO Positron style `https://basemaps.cartocdn.com/gl/positron-gl-style/style.json`,
and the same COL checklist UUID (exposed to the client via the project/map payload or a
`/config` value so it stays in sync with the backend property).

## External APIs used

- **CLB match** (proxied by our backend):
  `GET {clb}/dataset/{matchDataset}/match/nameusage?scientificName&authorship&rank&code&verbose=true`
  plus higher-classification params — **every ancestor at a supported rank**, not just the major
  Linnean ones. The endpoint's `Classification` bean accepts:
  `superkingdom, kingdom, subkingdom, superphylum, phylum, subphylum, superclass, class, subclass,
  superorder, order, suborder, superfamily, family, subfamily, tribe, subtribe, genus, subgenus,
  section`. We pass all ancestors whose rank is in this set and **omit `species`** (per the CLB
  matching guidance — the query name itself supplies the species). Ancestors at any other rank
  (e.g. clades, unranked) are skipped.
  Response `UsageMatchWithOriginal`: `usage{id,name,authorship,rank,status,namesIndexId,classification}`,
  `type` (EXACT/VARIANT/CANONICAL/AMBIGUOUS/NONE/UNSUPPORTED), `alternatives[]` (candidate list,
  richer when `verbose=true`). The matched **COL id** = `usage.id`.
- **CLB area geojson** (direct browser fetch): `GET {clb}/vocab/area/{gazetteer}:{areaId}`
  with `Accept: application/geo+json`.
- **GBIF density tiles** (direct browser, raster):
  `{gbif}/v2/map/occurrence/density/{z}/{x}/{y}@1x.png?srs=EPSG:3857&style=iNaturalist.poly&bin=hex&hexPerTile=64&hasCoordinate=true&hasGeospatialIssue=false&occurrenceStatus=PRESENT&checklistKey={COL_UUID}&taxonKey={colId}`
- **GBIF preflight count** (direct browser): `{gbif}/v1/occurrence/search?checklistKey={COL_UUID}&taxonKey={colId}&hasCoordinate=true&hasGeospatialIssue=false&occurrenceStatus=PRESENT&limit=0` → read `.count` (0 ⇒ grey/skip the layer).

## Component architecture

Backend (mirrors existing patterns — child-entity services, `CrossrefClient` proxy,
audit + optimistic locking, validation issues):

- `ClbMatchClient` — isolated HTTP proxy to CLB match (like `CrossrefClient`; `RestClient.builder()`
  static, base URL from config). `@MockitoBean`'d in tests.
- `ColMatchService` / `ColMatchController` — single-usage match: builds classification from the
  usage's ancestors, calls `ClbMatchClient`, maps to a candidate DTO list.
- `UsageIdentifierService` / controller — `PUT …/usages/{uid}/identifiers` (optimistic-locked).
- `MapDataService` / controller — subtree aggregation for the map.
- `ColMatchJobService` — the async bulk job (reuses task/work-session infra), writes ids +
  `col_*` issues.

Frontend:

- `api/*` clients for each endpoint; `NameUsage` type gains `alternativeId: string[]`.
- `child/DistributionMapPanel.tsx` (lazy-loads `maplibre-gl`) — layer control + match entry point.
- `child/MatchColModal.tsx` — candidate picker → `PUT identifiers`.
- Distribution tab renders the panel above the existing `ChildEntityTab` table.
- Project page gains a **GBIF layer** toggle and a **Match all to COL** action.

---

## Build order (phases — each independently testable & committable)

### Phase 1 — TypeMaterial lat/lon
- V11 adds `latitude`/`longitude DOUBLE PRECISION` to `type_material`.
- `TypeMaterialRequest`/`TypeMaterialResponse` gain `Double latitude, Double longitude`;
  mapper `SELECT`/`INSERT`/`UPDATE` include both.
- Types tab form: two `number` fields (Latitude, Longitude).
- Extend `TypeMaterialIT` to round-trip lat/lon.

### Phase 2 — Name-usage identifiers: expose + write
- Add `List<String> alternativeId` to `NameUsageResponse`; set it in `NameUsageService.toResponse`.
- `PUT /api/projects/{pid}/usages/{uid}/identifiers` — body `{ alternativeId: string[], version }`;
  CAS on version (409 on stale), audit (`Operation.UPDATE`, entity `name_usage`), returns the
  updated `NameUsageResponse`. A helper merges a new `col:` id in, replacing any existing `col:`
  entry (one col id per usage; other scopes preserved).
- Mapper: `updateAlternativeId(projectId, id, alternativeId, modifiedBy, version)` (CAS).
- Frontend: `NameUsage.alternativeId`; read-only col-id line in the Details tab.
- IT: set → replace col: → 409 on stale version.

### Phase 3 — COL match proxy (single usage)
- `ClbMatchClient.match(sciName, authorship, rank, code, classification)` →
  `GET {clb}/dataset/{matchDataset}/match/nameusage?...&verbose=true`. CLB 5xx/timeout → 502
  "COL matching unavailable"; `type=NONE`/no usage → empty candidates.
- `ColMatchService.match(userId, pid, usageId)`: loads the usage, derives `code` from the
  project `nomCode` (uppercased), builds the classification from ancestors
  (`NameUsageMapper.findClassification` — recursive CTE returning `[{rank,name}]`, reused by the
  bulk job), calls the client, maps to `ColMatchCandidate[]`.
- Classification mapping: for each ancestor, map its rank to the matching CLB query param via a
  small allow-list (the supported ranks above; `section_botany`/`section_zoology` → `section`),
  skipping `species` and any rank not in the set. Every supported ancestor rank is passed, not
  just the majors — a `subgenus`/`superfamily`/`tribe` ancestor is included when present.
- `ColMatchCandidate = { colId, name, authorship, rank, status, matchType, classification }`
  (best match first, then `alternatives`).
- `GET /api/projects/{pid}/usages/{uid}/col-match` → `ColMatchCandidate[]` (read-only; stores nothing).
- IT: `ClbMatchClient` `@MockitoBean`'d — EXACT with alternatives, and NONE.

### Phase 4 — Project GBIF-layer setting
- V11 `project.gbif_occurrence_layer` (default true).
- `ProjectResponse.gbifOccurrenceLayer: boolean`; `UpdateProjectMetadataRequest.gbifOccurrenceLayer`
  (carry-over in update). Mapper insert/update/select include it.
- Project page: a `Switch` "Show GBIF occurrence layer on maps".
- IT/asserts: default true on create; toggled off round-trips.

### Phase 5 — Subtree map-data endpoint
- `GET /api/projects/{pid}/usages/{uid}/map` →
  ```
  { colId: string|null,
    distributions: [{ usageId, name, focal, gazetteer, areaId, area }],
    typeSpecimens:  [{ usageId, name, focal, status, latitude, longitude, locality }] }
  ```
  where `focal = (usageId === uid)`. `colId` = the `col:` entry parsed from the focal usage's
  `alternativeId` (bare id, no scope), else null.
- Recursive CTEs from the focal node down: `findSubtreeDistributions`, `findSubtreeTypePoints`
  (type points require `latitude` and `longitude` non-null). Names joined for labels.
- Read access = any project role.
- IT: build a small genus→2 species tree; assert focal vs descendant flags and that only
  lat/lon-bearing type material appears.

### Phase 6 — Frontend map + single-taxon match
- `npm i maplibre-gl`; `DistributionMapPanel` is imported via `React.lazy(() => import(...))`
  behind `<Suspense>` so maplibre is a separate chunk.
- Pure, unit-tested helpers: `colIdFrom(alternativeId): string|null`, `gbifTileUrl(colId, checklistKey)`,
  `areaGeojsonUrl(gazetteer, areaId)`.
- Panel behaviour:
  - Fetches `…/map` + the project (`gbifOccurrenceLayer`).
  - Layers & default visibility: Distribution(focal)=on, Type(focal)=on, Distribution(children)=off,
    Type(children)=off, GBIF raster = on iff `gbifOccurrenceLayer && colId` (still user-toggle in the
    control). Checkbox layer control reflects/*toggles* each.
  - Distribution polygons: for each record with `gazetteer`+`areaId`, fetch geojson, add fill+line
    (focal vs children coloured differently); free-text-only `area` rows are listed as "not mappable".
  - Type points: circle markers (focal vs children coloured); popup name · status · locality.
  - GBIF raster: added only when enabled+colId; optional preflight count greys it at 0.
  - No colId → an inline notice with the **Match to COL** button instead of the GBIF layer.
- `MatchColModal` (editors only): `GET …/col-match` → radio list (name · authorship · rank ·
  status · matchType, with classification to disambiguate homonyms) → **Use this** →
  `PUT …/identifiers` (merge `col:<id>`), invalidates `['usage',…]`, `['map',…]`. `NONE` →
  "No COL match found".
- Tests: mock `maplibre-gl` (jsdom has no WebGL) to assert layer/branch logic; MSW for the
  match→PUT flow; helper unit tests.

### Phase 7 — Bulk project-wide COL match job
- `POST /api/projects/{pid}/col-match` (editor) → starts an **async job** via the existing
  task/work-session mechanism; returns a task id. Response 202.
- Job walks every usage; per name re-matches (Phase-3 client + classification, `verbose=true`)
  and reconciles against the stored `col:` id:
  | stored | matched | action | flag |
  |---|---|---|---|
  | `col:X` | `col:X` | none (verified) | clear prior `col_*` |
  | `col:X` | `col:Y` | replace id | `col_id_updated` (INFO) |
  | none | `col:Y` | add id | `col_id_added` (INFO) |
  | any | none | leave | `col_match_missing` (WARN) |
- Id writes go through the same merge/CAS path as Phase 2 (off-request → `AuditService` records
  an ungrouped change, like the dev seeder). Flags recorded via `IssueMapper` with rule keys
  `col_match_missing` / `col_id_added` / `col_id_updated`; the job **clears its own prior `col_*`
  issues per usage** before writing new ones (idempotent re-run).
- **Reconciliation constraint:** per-usage ValidationRule reconciliation must be scoped to known
  rule keys so it never deletes `col_*` flags. Verify the existing reconcile query; if it deletes
  by "not in fresh rule set", restrict it to the ValidationRule key set. Add a regression IT
  (edit a usage that has a `col_*` flag → flag survives).
- Politeness: bounded/sequential calls to CLB (small fixed concurrency or a short delay); the job
  is `@Async`. Log a summary (added/updated/no-match counts).
- Frontend: **Match all to COL** on the Project page → starts the job, shows progress + summary;
  flags appear in the Issues dashboard and per-usage Issues tab (filterable by the new rule keys).
- IT: 3-usage project (one with a stale `col:`, one unmatched, one new) → asserts id writes + the
  three flag types + idempotent re-run + col-flag survives a subsequent usage edit.

## Error handling (summary)

- CLB/GBIF unreachable → match proxy returns 502; map layers degrade to basemap; the panel shows
  a non-blocking "occurrences unavailable" note.
- Stale version on identifiers PUT → 409 (reload-and-retry, as elsewhere).
- Area geojson 404 (unknown area code) → that polygon is skipped; the row shows in "not mappable".
- Bulk job errors on a single name are caught, flagged `col_match_missing`, and the job continues.

## Testing strategy (summary)

- Backend: `ClbMatchClient` `@MockitoBean`'d; ITs for identifiers PUT, col-match mapping (verbose
  alternatives + NONE), the `/map` subtree aggregation (focal flags, lat/lon filter), the bulk job
  (all four reconcile branches + idempotency + col-flag survival), TypeMaterial lat/lon round-trip,
  project setting default/round-trip.
- Frontend: pure-helper unit tests; maplibre mocked for the panel; MSW for match/identifiers/map.
- Full `mvn verify` (JDK 25) + `vitest` + `npm run build` green per phase before commit.

## Caveats / follow-ups

- **Release id-space:** `3LXR` usage ids are used as GBIF `taxonKey` against COL's checklist UUID;
  if GBIF ingested a different COL release, an extended-only taxon's GBIF layer may not light up.
  Acceptable (optional/informative layer; key is configurable).
- **Subtree size:** recursive aggregation is unbounded for very high taxa; fine at project scale.
  Follow-up: cap/paginate or lazy-load descendant layers if a genus returns thousands of points.
- **Distribution editing on the map** (draw/pick areas) is future work.
