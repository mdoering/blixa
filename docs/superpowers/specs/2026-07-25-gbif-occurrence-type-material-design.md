# GBIF occurrence → TypeMaterial import

*2026-07-25*

## Goal

On a name's **Types** tab, pull type specimens (holotypes, syntypes, …) for the taxon from GBIF's
occurrence store and let the curator import selected ones as TypeMaterial records. The name is
resolved to a **COL** taxon (the GBIF backbone is deprecated in favour of COL), and GBIF occurrences
are queried against GBIF's COL checklist.

## Confirmed against the live GBIF API

- `GET /occurrence/search?checklistKey=<COL>&taxonKey=<COL taxonID>&typeStatus=Holotype` returns the
  type specimens, using the **COL taxonID** (e.g. `4CGXP`) as `taxonKey` — the exact value Blixa
  stores in a usage's `col:<id>` alternativeId. `checklistKey` is the COL dataset key in GBIF,
  already configured as `coldp.col.gbif-checklist-key` (`7ddf754f-…`). The old backbone usageKey
  returns 0, confirming we must use the COL id, not a backbone key.
- Occurrence fields map onto TypeMaterial almost 1:1 (see mapping below).
- `typeStatus` is a repeatable filter; querying the meaningful type statuses (excluding `NotAType`)
  returns only genuine type specimens.

## COL id resolution

1. If the usage carries a `col:<id>` alternativeId (the existing "Match to COL" feature stores it),
   use that id directly.
2. Otherwise name-match to COL via the existing `ClbMatchClient` (`coldp.col.match-dataset`, the COL
   dataset in ChecklistBank) → the matched COL id. The COL id is stable across CLB and GBIF, so the
   same id keys the GBIF occurrence query.
3. If neither yields a COL id, return an empty candidate list with a clear "no COL match" note — no
   error; the Types tab just shows nothing to import.

We do **not** persist a col: id as a side effect of this feature (matching for the query is
read-only); the user still uses the dedicated "Match to COL" action to store one.

## Backend

### GbifOccurrenceClient (new `@Component`)

Mirrors `ClbMatchClient`/`CrossrefClient`: an SSRF-guarded `RestClient` with a configurable base URL
(`coldp.gbif.base-url`, default `https://api.gbif.org/v1`) and the checklist key
(`coldp.col.gbif-checklist-key`). One method:

```java
List<GbifTypeOccurrence> findTypeSpecimens(String colTaxonId);
```

→ `GET /occurrence/search?checklistKey={col}&taxonKey={colTaxonId}&typeStatus={…set…}&limit=100`,
mapping each result to a `GbifTypeOccurrence` DTO (the fields below). Type-status filter is a fixed
curated set: Holotype, Lectotype, Neotype, Syntype, Paratype, Isotype, Paralectotype, Isolectotype,
Isoneotype, Epitype, Allotype, Cotype, Topotype, Type. Bounded (limit 100); if GBIF returns more, the
UI notes the cap.

### Field mapping (occurrence → TypeMaterial)

| GBIF occurrence | TypeMaterial |
|---|---|
| `typeStatus` | `status` |
| `scientificName` | `citation` (the specimen's identified name — lets the curator judge relevance) |
| `institutionCode` | `institutionCode` |
| `catalogNumber` | `catalogNumber` |
| `occurrenceID` (or `key` if absent) | `occurrenceId` |
| `key` | `link` = `https://www.gbif.org/occurrence/{key}` |
| `locality` | `locality` |
| `country` | `country` |
| `recordedBy` | `collector` |
| `eventDate` | `date` |
| `sex` | `sex` |
| `decimalLatitude` / `decimalLongitude` | `latitude` / `longitude` |

### Service + endpoints

`GET /api/projects/{pid}/usages/{id}/gbif-types` (any member) → `{ colId, colName, truncated,
candidates: [TypeMaterialRequest-shaped + occurrenceId + alreadyImported] }`. Resolves the COL id,
queries GBIF, maps, and flags each candidate whose `occurrenceId` already exists as a TypeMaterial on
this usage (dedup, so a re-open doesn't offer duplicates).

Import reuses the existing TypeMaterial child-create path — the modal POSTs the selected candidates
one per `POST /usages/{id}/type-material` (the existing `TypeMaterialController.create`), so no new
write endpoint and the normal audit/validation fire. (If per-row latency is poor, a later optimization
is a bulk create; not v1.)

## Frontend

- `TypeMaterialTab` gains an **"Import from GBIF"** action (button/icon, editor-gated).
- A `GbifTypesModal` (modelled on `CompareClbModal`): on open it calls the endpoint, shows a spinner,
  then a checkbox list of candidates — each row showing typeStatus (badge), the specimen's
  scientificName, institution + catalog number, and country; already-imported rows are shown disabled
  with an "imported" tag. A header line shows the resolved COL name (or "no COL match"). The curator
  ticks rows and clicks **Import N**; each ticked row is created as a TypeMaterial, the Types list +
  `usageTypeMaterial` query invalidate, and a summary toast shows the count.
- `api/gbifTypes.ts`: `getGbifTypes(pid, usageId)` + reuse the type-material create wrapper.

## Testing

- **Backend unit**: the occurrence → TypeMaterial field mapping (pure), over a captured GBIF JSON
  fixture; the type-status query-param assembly.
- **Backend IT**: `GbifTypesIT` with the GBIF client pointed at a MockWebServer/stubbed base URL
  serving a canned occurrence-search response — asserts the endpoint resolves a `col:` id, maps
  candidates, and flags an already-imported occurrenceID as `alreadyImported`. (Same
  external-client-stubbing approach the Clb/Crossref ITs use; no live GBIF call in tests.)
- **Frontend**: the modal lists candidates, disables imported ones, imports the ticked rows (MSW
  handlers), and shows the "no COL match" empty state.

## Scope guardrails (v1)

Only the Taxon's own type specimens via its COL id / name-match (no free-text GBIF search); no writing
a col: id as a side effect; no images/multimedia from the occurrence; no re-sync/update of a
previously imported specimen (create-only, deduped by occurrenceID). Live verification against real
GBIF is a post-merge dev check (the client is stubbed in tests).

## Rollout

Backend first (client + mapping + endpoint + IT), then the modal. Commit per green layer on `main`.
Blocked behind the project-view-tabs frontend task (requested first).
