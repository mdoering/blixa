# Darwin Core Archive (DwC-A) import — design

*2026-07-24*

## Goal

Let a user upload a Darwin Core Archive (`.zip`) and import its checklist into a staging project,
via the same pipeline ColDP and TextTree already use. **Import only — no DwC-A export** (explicitly
out of scope).

## Fit with the existing pipeline

The import pipeline is already pluggable (see `coldp/imprt`):

- `SourceFormatAdapter.materialize(file, dir, title, maxBytes)` turns an upload into a
  **ColDP-readable staging dir** (`NameUsage.tsv` + `metadata.yaml` + optional child TSVs).
- `ImportRunService.run()` then loads that dir unchanged — it re-parses `scientificName` with the
  name-parser, maps `status` via `ColdpParse.parseStatus`, handles pro-parte synonyms, and reads the
  child TSVs it already supports: Distribution, VernacularName, Media, SpeciesEstimate, NameRelation,
  TaxonProperty, TypeMaterial, Reference.

So DwC-A import is **a new adapter + a converter**, mirroring `TxtTreeAdapter` + `TxtTreeToColdp`,
with **no change to `run()`/`loadTransactional`**:

- `SourceFormat.DWCA` enum constant.
- `DwcaAdapter implements SourceFormatAdapter` — extracts the archive and delegates to the converter.
- `DwcaToColdp` — the mapping: reads the archive with `dwca-io`, writes `NameUsage.tsv`, child TSVs,
  and `metadata.yaml`.

## Dependency

Add `org.gbif:dwca-io:3.0.0` (stable; `dwc-api` is already a transitive compile dependency). Read
with `org.gbif.dwc.DwcFiles.fromCompressed(zipPath, tmpDir)` → `Archive`; iterate `StarRecord`s
(`archive.iterator()` / the star iterator), each exposing `record.core()` and
`record.extension(rowType)`. Each `Record` yields values by `Term` (`record.value(DwcTerm.xxx)`).

## Format detection (both formats are `.zip`)

`SourceFormat.detect(filename)` can't distinguish a DwC-A `.zip` from a ColDP `.zip`, so add a
content sniff: peek the uploaded zip's entries for **`meta.xml`** (the DwC-A descriptor) →
`SourceFormat.DWCA`; otherwise `COLDP`. `.txtree/.tsv/...` stay `TXTREE`. `ImportRunService.start()`
does the sniff before dispatching to the adapter (it already reads the multipart file there);
`MultipartFile.getInputStream()` can be reopened, so sniff-then-materialize is safe.

## Core: Taxon → NameUsage

Require the core `rowType` to be **`dwc:Taxon`**; reject an Occurrence-core (or other) archive with a
clear 400 ("DwC-A core must be a Taxon core"). Per core record, emit a `NameUsage.tsv` row:

| DwC term | ColDP term |
|---|---|
| `taxonID` (or `id`) | `ID` |
| `scientificName` | `scientificName` |
| `scientificNameAuthorship` | `authorship` |
| `taxonRank` | `rank` |
| `namePublishedInYear` | `publishedInYear` |
| `nomenclaturalStatus` | `nameStatus` (→ nomStatus) |
| `taxonRemarks` | `remarks` |
| `taxonomicStatus` | `status` (normalized, see below) |
| `parentNameUsageID` / `acceptedNameUsageID` | `parentID` (see classification) |

The importer re-parses `scientificName` for atomized fields + `nameType`/`parseState`, so the DwC
atomized name parts (`genus`, `specificEpithet`, …) are **not** mapped except where they feed the
flat-classification fallback below.

### Status normalization

DwC `taxonomicStatus` is free-ish; normalize to the ColDP vocab `ColdpParse.parseStatus` accepts:

| DwC `taxonomicStatus` (case-insensitive, `_`/space-insensitive) | ColDP `status` |
|---|---|
| `accepted`, `valid` | `accepted` |
| `synonym`, `homotypic synonym`, `heterotypic synonym`, `objective synonym`, `subjective synonym`, `proParteSynonym` | `synonym` |
| `misapplied` | `misapplied` |
| `doubtful`, `provisionally accepted`, `unassessed` | `provisionally accepted` |
| blank / unknown | (omit → importer defaults to UNASSESSED) |

A synonym row gets its `parentID` = `acceptedNameUsageID` (the importer turns a non-accepted row's
`parentID` into a `synonym_accepted` link — the same trick `TxtTreeToColdp` uses for synonyms).
Pro-parte synonyms pointing at multiple accepted names are left to the importer's existing pro-parte
handling; the converter emits one row per `taxonID` (multi-accepted pro-parte beyond the archive's
single `acceptedNameUsageID` column is out of scope for v1).

## Classification: normalized + flat fallback (per row)

**Normalized (preferred).** If the row has a `parentNameUsageID` (accepted) or `acceptedNameUsageID`
(synonym), use it directly as `parentID`. Rows with neither are roots.

**Flat fallback.** If a row has *no* parent/accepted link, synthesize its higher classification from
the atomic Linnaean rank columns present, in order: `kingdom, phylum, class, order, family, genus`
(+ `subgenus` between genus and species). Algorithm:

1. Build the ordered list of `(rank, name)` from the non-blank rank columns.
2. **Get-or-create** a synthetic higher-taxon node per level, **deduped by its full ancestral path**
   (the concatenation of ancestor names down to that rank) — so every row under
   `Animalia|Chordata|…|Felidae` shares one `Felidae` node, while a homonymous genus in another
   family stays distinct. Chain them (kingdom = root, each deeper rank's parent = the one above).
   Synthetic ids are reserved (prefixed, e.g. `__dwca_synth_<seq>`) so they never collide with a real
   `taxonID`.
3. Attach the actual row under the **deepest synthesized ancestor strictly above the row's own rank**.
   If the row's own rank equals a present column and the column value equals the row's own canonical
   name (the row *is* that classification node — e.g. a `family` row with `dwc:family` = its name),
   don't create a duplicate synthetic node: register the row's own id as the node for that path so its
   descendants parent onto it.
4. Synthetic nodes are emitted as extra `accepted` `NameUsage.tsv` rows (rank + scientificName = the
   column value); the importer allocates real project ids for them like any other row.

Mixed archives (some rows linked, some flat) are handled per-row: a linked row never triggers
synthesis. If an archive has neither links nor rank columns for a row, it becomes a root (a flat list),
which is a legitimate — if unstructured — import.

`log`/import-summary note: the number of synthesized higher taxa, so the user sees the tree was
inferred, not present in the source.

## Extensions

Each extension row is keyed to its core `taxonID` (`coreID`), written to the matching child TSV the
importer already reads. Missing extensions are simply absent.

**`gbif:VernacularName` → VernacularName.tsv**

| DwC | ColDP |
|---|---|
| `vernacularName` | `name` |
| `language` | `language` |
| `countryCode` / `country` | `country` |
| `sex` | `sex` |
| `isPreferredName` | `preferred` |

**`gbif:Distribution` → Distribution.tsv**

The importer reads ColDP Distribution as `area, areaID, gazetteer, establishmentMeans, threatStatus,
referenceID, remarks` — the DwC mapping targets exactly those:

| DwC | ColDP |
|---|---|
| `locality` (or `countryCode` when no locality) | `area` |
| `locationID` | `areaID` (+ `gazetteer` from the id's scheme when recognizable, else blank) |
| `establishmentMeans` | `establishmentMeans` |
| `threatStatus` | `threatStatus` |
| `occurrenceStatus` | `remarks` (no dedicated ColDP column the importer reads) |

Note: the importer attaches child entities only to **accepted** taxa (it skips a distribution/
vernacular whose taxon isn't accepted), so extension rows on synonyms are dropped — consistent with
the taxon-level model.

**`gbif:SpeciesProfile` → folded onto the core NameUsage row** (not a child TSV): `isExtinct` →
`extinct`; `isMarine`/`isFreshwater`/`isTerrestrial`/`isBrackish` → `environment` (multi-value ColDP
`environment` on NameUsage.tsv). These land in `taxon_info` and are now visible/editable in the
**Biology** tab (shipped 2026-07-24).

## Metadata

`metadata.yaml` is required by the importer. Use the uploaded `title`; if blank, best-effort read the
archive's `eml.xml` `<dataset><title>` and `<abstract>` for title/description (a lightweight XML read,
tolerant of a missing/››malformed EML). Minimal `ColdpMetadataDto`.

## Frontend

**No new UI flow** — auto-detection means the existing import upload accepts a DwC-A `.zip`
transparently (same `POST /api/projects/import`). Update the upload form's helper text to list
Darwin Core Archive alongside ColDP and TextTree.

## Testing (TDD)

- **`DwcaToColdpTest`** (unit) over tiny hand-built archives (a temp dir with `meta.xml` + core/
  extension TSVs, or the `dwca-io` builder): normalized tree; flat-only synthesis (dedup of shared
  higher taxa; leaf attached under genus; a row that *is* its own classification node); a synonym via
  `acceptedNameUsageID`; status normalization; each extension; SpeciesProfile → extinct/environment.
- **`DwcaImportIT`** (real Postgres) — upload a fixture `.zip` through the real
  `POST /api/projects/import`, run the job, assert usages + tree + synonyms + vernaculars +
  distributions + extinct/environment landed, and the import-run summary reports counts.
- A **format-detection** test: a ColDP `.zip` and a DwC-A `.zip` each route to the right adapter.
- Fixtures: tiny archives generated in `src/test/resources/dwca/` (a few rows), kept minimal.

## Scope guardrails (v1 excludes)

References/citations extension + `dwc:namePublishedIn` free-text → Reference.tsv; type material
(`gbif:TypesAndSpecimen`); multimedia (`gbif:Multimedia` → Media); measurement facts;
`dwc:higherClassification` pipe-delimited string parsing (only the atomic rank columns are used);
multi-target pro-parte synonyms; and DwC-A **export**.

## Rollout

Backend only (no meaningful UI). Order: dependency + `SourceFormat.DWCA` + detection; `DwcaToColdp`
core + classification (unit-tested); extensions; `DwcaAdapter` + wiring; `DwcaImportIT`. Commit per
green layer on `main`. Update the backlog's DwC-A item when shipped.
