# Name & taxon child-entities (relations, types, vernacular, distribution, media, estimates, properties)

**Date:** 2026-07-09
**Scope:** Seven ColDP child-entities hung off a name usage, with CRUD + a tabbed detail UI. Built
one at a time on a shared pattern. Essential-fields only (per brainstorming).

## Applicability

- **Name-level (any usage, incl. synonyms):** `name_relation`, `type_material`.
- **Taxon-level (ACCEPTED usages only):** `vernacular`, `distribution`, `media`, `estimate`,
  `property`. Create is 400 on a non-accepted usage; `NameUsageService.demote` deletes all five for
  the node when it becomes a synonym (extends the existing taxon_info drop, keeping the invariant).

## Shared backend pattern (per entity `X`)

- Table `x(project_id INT, id INT, usage_id INT, …fields…, modified timestamptz default now(),
  modified_by INT, version INT default 0, PRIMARY KEY (project_id, id),
  FOREIGN KEY (project_id, usage_id) REFERENCES name_usage(project_id, id) ON DELETE CASCADE)`.
  All in **one migration `V10__child_entities.sql`**. Vocab fields are **TEXT** (frontend Selects
  supply the values; no per-entity enum handlers — keeps 7 entities lean); `preferred` boolean,
  `estimate` integer.
- `XMapper` (annotation): `findByUsage(pid, usageId)` (ordered), `insert(X)` (id via `IdSeqMapper`),
  `update(X)` (version CAS → 0 rows = 409), `delete(pid, id)`, `deleteByUsage(pid, usageId)` (demote).
- `XService`: `list/create/update/delete(userId, pid, usageId?, id, req)` — editor-gated; create
  validates the usage is in-project (and ACCEPTED for the 5 taxon entities); audits (`AuditService`,
  entityType `"x"`) and publishes a `ValidationEvent.forUsage`.
- `XController` under `/api/projects/{pid}/usages/{uid}/x` — GET (list), POST (create), PUT `/{id}`,
  DELETE `/{id}`.
- DTOs: `CreateXRequest`, `XResponse` (records).

## Fields (essential set)

- **NameRelation** (any): `relatedNameId` (→ another usage in project), `type` (NomRelType, e.g.
  basionym/homotypic/spelling correction/replacement name/later homonym), `referenceId`, `page`, `remarks`.
- **TypeMaterial** (any): `citation`, `status` (TypeStatus, e.g. holotype/lectotype/neotype/paratype/
  syntype), `institutionCode`, `catalogNumber`, `occurrenceId` (GBIF — for later occurrence import),
  `locality`, `country`, `collector`, `date`, `sex`, `referenceId`, `link`, `remarks`.
- **VernacularName** (accepted): `name`, `language`, `country`, `sex`, `preferred` (bool),
  `referenceId`, `remarks`.
- **Distribution** (accepted): either a free-text `area` OR `areaId` + `gazetteer` (the preferred,
  map-ready form), `establishmentMeans`, `threatStatus`, `referenceId`, `remarks`.
- **Media** (accepted): `url`, `type` (image/video/audio), `title`, `creator`, `license`, `link`, `remarks`.
- **SpeciesEstimate** (accepted): `estimate` (int), `type` (EstimateType), `referenceId`, `remarks`.
- **TaxonProperty** (accepted): `property`, `value`, `page`, `referenceId`, `remarks` (ordinal deferred).

## Frontend

- **`api/<entity>.ts`** per entity: `list/create/update/delete`.
- **Tabbed detail:** refactor `TaxonDetail` so its body is Mantine `Tabs`: **Details** (the existing
  edit form + parsed section), **Names** (relations), **Types**, **Vernacular**, **Distribution**,
  **Media**, **Estimates**, **Properties**, **Synonyms**, **Issues**. The five taxon tabs render only
  when the usage is ACCEPTED.
- **Reusable `ChildEntityTab`**: a generic list + "Add" + per-row edit/delete over a small
  `<EntityForm>` (each entity supplies its columns + form fields). Owner/editor gated; queries keyed
  `['<entity>', pid, usageId]`, invalidated on write.
- Vocab Selects hardcode the standard ColDP values; `relatedNameId` uses a name-search picker
  (reuse the usage search); `referenceId` uses a reference picker (search references).

## Build order (each: migration slice already in V10 → mapper/service/controller/DTO → frontend tab → tests → commit)

1. NameRelation (template) 2. TypeMaterial 3. Vernacular 4. Distribution 5. Media 6. Estimate 7. Property.
The tabs refactor + `ChildEntityTab` land with #1.

## Testing / verification

Per entity: mapper IT (CRUD + cascade), API IT (create/list/update/delete + accepted-only 400 for
taxon entities + demote-drops-them), frontend api tests + a tab test. Browser: on seeded Felidae,
add a basionym relation + a holotype to a name, and a vernacular/distribution to an accepted taxon.

## Out of scope

Full ColDP columns (lat/long/altitude/host/associatedSequences on TypeMaterial; degreeOfEstablishment/
pathway/year/season/lifeStage on Distribution; ordinal on Property); GBIF occurrence import (the
`occurrenceId` field is the hook); area maps; vocab-backed validation of the TEXT vocab fields.
