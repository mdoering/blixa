# ColDP Export + Import — design

**Status:** approved (brainstorm) · **Date:** 2026-07-10

## Context & goal

The editor stores a taxonomic dataset that is already modelled closely on the
[ColDP](https://github.com/CatalogueOfLife/coldp) format. This feature adds full
round-tripping:

1. **Export** a project to a ColDP archive (a `.zip` of TSV files + `metadata.yaml`), as an
   async job producing a downloadable file.
2. **Import** a ColDP archive into a **new** project, as an async job — a generic, unsupervised
   load that also serves as a temporary staging store for later filtering/matching.

These share a format-mapping core (our rows ↔ ColDP columns, both directions) built on a TSV
reader/writer and zip.

## Non-goals (this design)

- **No merge into an existing project.** Import always creates a new project. Reconciling an
  imported (staging) project's names into another project is a **separate, later, user-supervised
  feature** built on name-matching we still need to develop. This design's "always new project" is
  what feeds it.
- No DwC-A / ACEF / other formats — ColDP only.
- No incremental/streaming download of an in-progress export (the file is produced whole, then
  downloaded).
- No child-entity source-id preservation (only name-usages and references preserve source ids; see
  Identity).

## Identity strategy — drop `coldp_id`

We **remove the `coldp_id` column** from `name_usage`, `reference`, and `author` (migration +
mapper/DTO cleanup), including the `UNIQUE (project_id, coldp_id)` constraints. A record's identity
is its own per-project numeric `id`. Any external/source identifier lives **only** as a CURIE in the
existing `alternative_id TEXT[]` (`scope:id`, e.g. `col:6W3C4`, `gbif:2704179`).

- **Export** writes each record's numeric `id` (as a string) into the ColDP `ID` column; every
  cross-reference (`parentID`, `basionymID`, `referenceID`, `relatedNameID`, child `taxonID`/`nameID`)
  writes the **target** record's `id`. The `alternativeID` column carries the record's CURIEs. The
  archive is therefore internally consistent and valid ColDP (ids are strings; numeric strings are fine).
- **Import** treats the archive's ColDP `ID`s as **foreign strings used only transiently** to wire up
  cross-references (see Import). They are never persisted as an id column. Optionally (user choice)
  they are preserved as `<scope>:<sourceId>` CURIEs in `alternative_id`.

## Data-model changes

- **Vxx `drop_coldp_id.sql`:** drop `coldp_id` (and its unique constraints) from `name_usage`,
  `reference`, `author`. Update every mapper SELECT/INSERT/UPDATE and any DTO/POJO field that
  references it (`NameUsageMapper`, `ReferenceMapper`, author mapper, `DevSampleData` if it sets it).
- **`export_run`** table: `id BIGINT PK, project_id BIGINT FK, status TEXT (RUNNING|DONE|FAILED),
  file_path TEXT, file_name TEXT, file_size BIGINT, counts (per-entity or a JSON summary),
  started_at, finished_at, error TEXT`. Partial unique index `WHERE status='RUNNING'` (one active
  export per project). Artifact is a **file** at `file_path` under a configured dir — NOT a DB blob.
- **`import_run`** table: `id BIGINT PK, project_id BIGINT FK NULL (the created project, set once
  known), status, source_name TEXT (uploaded filename), preserve_ids BOOLEAN, id_scope TEXT,
  counts, issues (JSON list of non-fatal problems), started_at, finished_at, error`.

## Config

- `coldp.export.dir` — directory for export `.zip` files (default under the app data/temp dir).
  **Blue-green note:** must be shared storage so either instance can serve a download (mirrors CLB's
  Apache-served download area). A **retention sweep** deletes export files + rows older than
  `coldp.export.ttl` (default e.g. 7 days).
- `coldp.import.max-bytes` — upload size cap (reject larger with 413/400).

## Reuse the CLB ColDP io (don't hand-roll parsing)

The CLB backend already has mature ColDP io, resolvable from `~/.m2` as SNAPSHOT artifacts (same kind
of dependency as our existing `org.catalogueoflife:vocab`). **Depend on `org.catalogueoflife:reader`**
and reuse:
- **`life.catalogue.csv.ColdpReader`** (import) — encodings/BOM, filename resolution, header→term
  mapping, both the combined `NameUsage` and the split `Name`+`Taxon`+`Synonym` forms, and the
  foreign-key schemas. This replaces our own parser + the combined/split detection.
- **`life.catalogue.coldp.ColdpTerm`** (both directions) — the **authoritative** enum of every ColDP
  file + column (`ColdpTerm.RESOURCES`). Column names come from here, so the mapping table below is
  keyed by our field → its ColdpTerm (no README-guessing).
- **`life.catalogue.common.io.TermWriter` / `TabWriter`** (export) — term-keyed TSV writing.
- **`life.catalogue.api.model.Identifier.Scope`** — the id-scope enum for the import UI (below).

Caveats: `reader` transitively pulls `api` (Jackson 2 — distinct package from our Jackson 3
`tools.jackson`, no clash), `coldp`, name-parser, univocity, guava, woodstox; manage dependency scope
(exclude junit-vintage / test artifacts, avoid a logging-backend clash — logback is shared with Spring
Boot). SNAPSHOT resolvability must hold in the CI build env (same constraint that already applies to
`vocab`). If the transitive weight proves problematic, the fallback is to depend only on `coldp`
(ColdpTerm) and hand-roll the univocity read/write — but the reader reuse is preferred.

## Format mapping (our tables → ColDP files)

All data files are **TSV**; `metadata.yaml` is YAML. Column names are the `ColdpTerm` values; the
mapping of *our fields* → ColdpTerm is below.

### `metadata.yaml` ← `project`
title, alias→name/alias, description, license, `geographic_scope`→geographicScope,
`taxonomic_scope`→taxonomicScope. (No creator/contact — we don't store them.) Import reads the same
fields to create the new project. **Note:** `nom_code` is **not** a metadata.yaml key — the
nomenclatural code travels on the `NameUsage.code` column (below); on import, `project.nom_code` is
taken from the rows' `code`.

### `NameUsage.tsv` ← `name_usage` (+ `taxon_info`, `synonym_accepted`)
Combined name+usage form. Columns (our field → ColDP column):
`id→ID`, `parent_id→parentID`, `basionym_id→basionymID`, `status→status`, `rank→rank`,
`scientific_name→scientificName`, `authorship→authorship`, `uninomial→uninomial`,
`genus→genericName`, `infrageneric_epithet→infragenericEpithet`, `specific_epithet→specificEpithet`,
`infraspecific_epithet→infraspecificEpithet`, `cultivar_epithet→cultivarEpithet`, `notho→notho`,
`combination_authorship→combinationAuthorship`, `combination_ex_authorship→combinationExAuthorship`,
`combination_authorship_year→combinationAuthorshipYear`, `basionym_authorship→basionymAuthorship`,
`basionym_ex_authorship→basionymExAuthorship`, `basionym_authorship_year→basionymAuthorshipYear`,
`sanctioning_author→sanctioningAuthor`, `nom_status→nameStatus`,
`published_in_reference_id→nameReferenceID`, `published_in_year→publishedInYear`,
`published_in_page→publishedInPage`, `published_in_page_link→publishedInPageLink`, `gender→gender`,
`etymology→etymology`, `name_phrase→namePhrase`, `reference_id[]→referenceID` (comma-joined taxonomic
refs), `link→link`, `remarks→remarks`, `ordinal→ordinal`, `alternative_id[]→alternativeID`
(comma-joined), `project.nom_code→code`. From `taxon_info` (accepted usages only):
`extinct→extinct`, `environment[]→environment`, `temporal_range_start→temporalRangeStart`,
`temporal_range_end→temporalRangeEnd`. For an accepted row, `parentID` is the classification parent.
**Synonyms** (a usage with `synonym_accepted` links): emit one row per accepted link — the **primary**
link (first by accepted id) uses the synonym's own `id` as `ID` with `parentID` = that accepted; each
**additional (pro parte)** link emits a row with a deterministic derived `ID = "<synonymId>-<acceptedTaxonId>"`
and `parentID` = that accepted, repeating the name fields. (Combined NameUsage has one `parentID`/row;
this keeps the primary id stable and pro-parte ids reproducible. Consequence: re-importing such an
archive expands a pro-parte synonym into N synonym usages — provenance kept via `alternativeID`.) A
synonym with no accepted link emits a single row with empty `parentID`.

### `Reference.tsv` ← `reference`
`id→ID`, `citation→citation`, `type→type`, `author→author`, `editor→editor`, `title→title`,
`container_title→containerTitle`, `issued→issued`, `volume→volume`, `issue→issue`, `page→page`,
`publisher→publisher`, `doi→doi`, `isbn→isbn`, `issn→issn`, `link→link`, `alternative_id→alternativeID`.

### `Author.tsv` ← `author` (only if the project has authors)
`id→ID`, `given→given`, `family→family`, `suffix→suffix`, `affiliation→affiliation`,
`alternative_id→alternativeID`, and `abbreviation_botany` → its ColdpTerm Author column.

### Child-entity files (each `<...>_id.usage_id` → the parent usage's `id`)
`TypeMaterial.tsv` ← `type_material` (nameID=usage id; citation, status, institutionCode,
catalogNumber, occurrenceID, locality, country, collector, date, sex, latitude, longitude,
referenceID, link, remarks). `Distribution.tsv` ← `distribution` (taxonID; areaID, area, gazetteer,
establishmentMeans/status, threatStatus, referenceID, remarks). `VernacularName.tsv` ← `vernacular`
(taxonID; name, language, country, sex, preferred, referenceID, remarks). `Media.tsv` ← `media`
(taxonID; url, type, title, creator, license, link, remarks). `SpeciesEstimate.tsv` ← `estimate`
(taxonID; estimate, type, referenceID, remarks). `NameRelation.tsv` ← `name_relation`
(nameID=usage id, relatedNameID=related usage id, type, referenceID, page, remarks).
`TaxonProperty.tsv` ← `property` (taxonID; property, value, page, referenceID, remarks). Each child
row's ColDP `referenceID`/`taxonID`/`nameID` = the target record's `id`.

## Export architecture

- `ColdpMapping` (format core): per entity, maps our record → a `ColdpTerm`-keyed row (for export)
  and a `ColdpTerm`-keyed row → our record (for import). Column names/order come from `ColdpTerm`.
  Unit-tested round-trip (record→row→record) per entity.
- `ColdpWriter`: opens a `ZipOutputStream`; for each entity streams `mapper.findAllByProject(pid)`
  through a `TermWriter`/`TabWriter` (or univocity keyed by `ColdpTerm`) into a zip entry; writes
  `metadata.yaml` (SnakeYAML). Streams to the target file at `coldp.export.dir/{runId}.zip`.
- `ExportRunService`: `start(userId, pid)` — member read auth; pre-check + partial-unique-index guard
  (409 if an export is already RUNNING for the project); insert RUNNING `export_run`; kick off
  `@Async run(...)` via a `@Lazy self` proxy; the async run writes the zip, updates counts, marks
  DONE (or FAILED on error, deleting a partial file). `latest(pid)`, `get(pid, runId)`,
  `fileFor(pid, runId)` (returns the file for streaming; 404 if not DONE/missing).
- `ExportRunController`: `POST /api/projects/{pid}/export` (202), `GET …/{runId}`, `GET …/{runId}/file`
  (streams with `Content-Disposition: attachment; filename="{project}-coldp.zip"`), `GET …/latest`.
- `ExportRunRecovery` (startup) marks stale RUNNING → FAILED. `ExportRetentionSweep` (scheduled)
  deletes files+rows past the TTL.
- Frontend: **Export ColDP** on the Project page → start → poll (`refetchInterval` while RUNNING) →
  a **Download** link (`…/{runId}/file`) on DONE; loads the latest run on mount.

## Import architecture

- `POST /api/projects/import` — `multipart/form-data`: the `.zip` + form fields `preserveIds`
  (bool), `idScope` (string, required iff preserveIds). Auth: any authenticated user may import —
  they become the owner of the created project, mirroring `POST /projects`. Enforce
  `coldp.import.max-bytes`.
- Flow (async `import_run`, `@Async` via self-proxy):
  1. **Open with `ColdpReader`**: it resolves filenames/encodings and exposes the present schemas
     (combined `NameUsage`, or split `Name`/`Taxon`/`Synonym`, plus the child + reference/author
     files) as `ColdpTerm`-keyed rows. Require either `NameUsage` OR (`Name` + `Taxon`); neither →
     FAILED with a clear message (validated before the heavy work). Read `metadata.yaml` → create the
     new project (owner = the importing user); set `import_run.project_id`.
  2. **References + authors first** (no forward deps): for each row allocate our `id` via `id_seq`,
     insert, record `sourceId → id` in a transient `Map`. If `preserveIds`, add `<idScope>:<sourceId>`
     to the row's `alternative_id`.
  3. **Names/usages (pass 1)**: for the combined form, each `NameUsage` row → one `name_usage`
     (status drives accepted vs synonym). For the split form, each `Taxon` row and each `Synonym` row
     → one `name_usage`, copying the referenced `Name`'s fields (via the archive's `nameID`); our
     model is combined so a Name used by N usages yields N rows. Allocate `id`, parse the name via
     `NameParserService` (prefer atomized columns present in the archive; else parse
     `scientificName`+`authorship`), insert, record `sourceUsageId → id`. Defer parent/basionym/
     reference links. If `preserveIds`, add `<idScope>:<sourceId>` to `alternative_id`.
  4. **Link pass (pass 2)**: resolve `parentID`→`parent_id` (accepted) or a `synonym_accepted` link
     (synonym); `basionymID`→`basionym_id`; `nameReferenceID`→`published_in_reference_id`;
     `referenceID[]`→`reference_id[]`; `taxon_info` from the accepted rows' extinct/env/temporal.
     Unresolvable ids (not in the map) → skipped + a non-fatal `import_run.issues` entry.
  5. **Child entities**: insert each, resolving `taxonID`/`nameID`→usage id and `referenceID`→ref id
     via the maps (dangling → skipped + issue).
  6. Commit (one project = one transaction; fatal error → rollback, mark FAILED, delete nothing since
     nothing committed). Then run the **validation engine** over the new project (issues surface in
     the dashboard). Mark DONE with counts.
- `ImportRunController`: `POST /api/projects/import` (202 + run, incl. the new `projectId` once set),
  `GET /api/projects/import/{runId}` (poll). `ImportRunRecovery` (startup) marks stale RUNNING →
  FAILED (and, since a partial import rolled back, the created project may or may not exist — if the
  transaction didn't commit, no project; note this).
- **ID-scope vocab**: a small backend endpoint exposes `life.catalogue.api.model.Identifier.Scope`
  values (`col, gbif, wfo, tpl, tsn, ipni, if, zoobank, inat, ina` + generic `doi, url, urn, lsid,
  local`) — now on the classpath via the `reader`→`api` transitive dep — and the frontend renders them
  as the scope dropdown plus a free-text custom scope.
- Frontend: an **Import ColDP** action (Projects list / a global spot): upload `.zip` + a
  "preserve source identifiers" switch + (when on) an id-scope `Select` (known scopes + custom) →
  start → poll → on DONE, a link to the new project; non-fatal issues shown as a summary.

## Libraries

- **`org.catalogueoflife:reader`** (new dep) — `ColdpReader` (import), `ColdpTerm` (vocab),
  `TermWriter`/`TabWriter` (export), `Identifier.Scope` (id-scope UI); pulls univocity transitively.
- **SnakeYAML** — already on the classpath (Spring Boot) — for `metadata.yaml`.
- JDK `java.util.zip` for the archive.

## Error handling

- Export: a write/mapper failure → run FAILED + error, partial file deleted; download of a
  non-DONE/missing run → 404; export while one is RUNNING → 409.
- Import: malformed zip / neither NameUsage nor Name+Taxon present / oversize → FAILED (or 400 at
  upload for oversize) with a clear message; per-row parse and dangling-reference problems →
  collected in `import_run.issues` (non-fatal); a fatal DB error → rollback + FAILED.

## Testing

- **Round-trip IT** (the key test): seed the Felidae sample project → export → in the test, unzip and
  assert the files/rows; then import that zip (preserveIds on, a test scope) → assert a new project
  with matching entity counts, correctly-resolved parent/basionym/synonym/reference links, and the
  preserved `<scope>:<id>` alternativeIDs.
- `ColdpMapping` unit tests: record→row→record per entity (incl. array fields, nulls, quoting).
- A **split-form** import fixture (small `Name`+`Taxon`+`Synonym` archive) → asserts the flattening
  to combined `name_usage` rows + synonym links.
- Guard/lifecycle ITs mirrored from col-match: single-active export 409, startup stale-sweep,
  retention sweep; import size cap; missing-required-file failure.
- Frontend: export start/poll/download and import upload/poll (MSW).

## Build phases (each independently shippable)

0. **Drop `coldp_id`** — migration + mapper/DTO cleanup; full `mvn verify` green. (Foundation; no
   behavior change.)
1. **Format core** — add the `org.catalogueoflife:reader` dependency (manage exclusions/scope);
   `ColdpMapping` (all entities, both directions, keyed by `ColdpTerm`) over `ColdpReader` (read) and
   `TermWriter`/`TabWriter` (write) + zip helper + `metadata.yaml` read/write; expose `Identifier.Scope`.
   Unit-tested in isolation.
2. **Export** — `export_run` + service/job (file artifact, self-proxy async) + endpoints + guards
   (single-active, startup sweep, retention) + Project-page action + the export half of the
   round-trip IT.
3. **Import** — `import_run` + upload + parse (combined + split) + two-pass transient id-remap +
   name parsing + references/authors + preserve-ids/id-scope + child entities + validation + the full
   round-trip IT + split-form fixture + the Import UI.

## Caveats / future

- **Stable identifiers principle.** Per-project numeric ids should stay stable for the "same"
  record and not be reassigned unnecessarily — durable ids are a deliberate, strong feature. In *this*
  design ids are naturally new (import = a new project), but the principle governs the follow-on
  **supervised merge**: a matched name must keep the target's existing id, never get a fresh one.
  (Source ids are carried forward as `<scope>:<id>` CURIEs meanwhile.)
- **Supervised merge into an existing project** (via name-matching) is the intended follow-on that
  consumes staging projects created by import — its own design.
- **Blue-green**: `coldp.export.dir` must be shared storage; otherwise a download can 404 on the
  instance that didn't generate it. Object storage is the scale path.
- Large archives: one-transaction import is simplest/correct but memory- and lock-heavy for very
  large datasets; batching/streaming is a later optimization (note the limit).
- Child-entity source ids are not preserved (only name-usages + references), per scope decision.
