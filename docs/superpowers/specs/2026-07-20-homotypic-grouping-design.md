# Homotypic grouping — Side 1: in-taxon grouping & nested synonymy

*Design spec — 2026-07-20*
**Status:** Side 1 implemented 2026-07-20 (plan: docs/superpowers/plans/2026-07-20-homotypic-grouping-side1.md).

## Context

Blixa renders an accepted taxon's synonyms as a **flat list** (`SynonymList.tsx`). Real
synonymies are structured: some synonyms share a *type* with the accepted name or with
each other (they are **homotypic** — objective/nomenclatural synonyms, `≡`), the rest are
**heterotypic** (`=`), and each heterotypic name carries its own homotypic recombinations.
The COL portal renders this nested (e.g. under *Poa annua* L.: the recombination *Ochlopoa
annua* (L.) H.Scholz sits at the top homotypic to the accepted name; *Aira pumila* Pursh is a
separate heterotypic group with *Catabrosa pumila* (Pursh) Roem. & Schult. nested under it).

This spec covers **Side 1** of the "Homotypic grouping" backlog item: detect the homotypic
structure *within one accepted taxon's synonymy*, persist it, and render it nested. A later
**Side 2** spec covers cross-dataset consolidation of *accepted* names (scan the focal taxon's
subtree — typically a family — for homotypic groups containing more than one accepted name and
let the curator pick the single survivor, converting the rest to synonyms; the CLB reference is
`life.catalogue.basgroup.HomotypicConsolidator`). Side 1 builds the shared foundation — the
detection primitive and the `name_relation` storage model — that Side 2 reuses.

### How CLB does it (reference)

CLB separates three layers (`life.catalogue.basgroup`, module `core`/`dao`):

- **Detection is computed from authorship.** `BasionymSorter` buckets names by normalized
  terminal epithet, then clusters by author team (`AuthorComparator.compareStrict`: fuzzy
  author match + compatible/again-missing year), using basionym authorship when present else
  combination authorship. A name *without* parenthetical (basionym) authorship is the basionym;
  ones *with* it are recombinations. It never reads stored relations to decide.
- **Storage is derived** — `HomotypicConsolidator` writes results back as `NameRelation` rows
  (`BASIONYM`, `HOMOTYPIC`, `BASED_ON`, `SPELLING_CORRECTION`).
- **Display is relation-driven** — `TaxonDao.getSynonymy` partitions synonyms into homotypic /
  heterotypic / misapplied purely by the transitive closure of stored homotypic relations
  (recursive SQL), never re-comparing authorship at read time.

Blixa already has the raw materials: parsed `combinationAuthorship`/`basionymAuthorship`/year on
every usage (detection inputs), the `name_relation` child entity (storage), and a flat
`SynonymList` (to be replaced by the nested view). `org.catalogueoflife:api` — already on the
classpath via the `reader` dependency — ships `SciNameNormalizer` (epithet normalization) and
`AuthorshipNormalizer` (author normalization + a 2.4 MB alias table). Only the small clustering
loop is new; `AuthorComparator`/`BasionymSorter` themselves live in CLB's heavy `dao`/`core`
modules and are deliberately **not** pulled in.

## Decisions (settled during brainstorming)

1. **Two sub-projects, Side 1 first.** In-taxon grouping + nested synonymy now; cross-dataset
   consolidation later.
2. **Detection = auto-detect + curator confirms.** A "Group synonyms" action proposes groups;
   the curator accepts/adjusts before anything is persisted. Editors can *also* assert any name
   relation by hand via the existing `NameRelations` tab (the manual escape hatch).
3. **Basionym-anchored, full vocabulary.** Detection designates a basionym and links
   recombinations via `basionym` relations; falls back to `homotypic` when no basionym is
   discernible. The curator can refine the subtype across the full ColDP vocab
   (`basionym` / `homotypic` / `spelling correction` / `based on` / `replacement name` /
   `superfluous`).
4. **`name_relation` is the single source of truth; `basionym_id` is dropped.** The
   `name_usage.basionym_id` column (currently pure import↔export plumbing — never surfaced in
   any response or UI) is removed. Import converts ColDP `basionymID` into a `basionym`
   name_relation; export derives `basionymID` back from that relation.
5. **Detection engine is a compact, in-repo `BasionymSorter`-lite** reusing `SciNameNormalizer`
   + `AuthorshipNormalizer`, working off the parsed authorship fields already stored on each
   usage. No heavy CLB dependency, no name-parser call at detection time.

## Storage model

The `name_relation` table (V10) is unchanged in shape and already holds two-name relations:
`usage_id`, `related_usage_id`, `type` (TEXT, ColDP `NomRelType`), `reference_id`, `page`,
`remarks`, `version`. A `basionym` relation points **recombination (`usage_id`) → basionym
(`related_usage_id`)**, matching ColDP's direction ("Name has basionym relatedName").

The homotypic relation vocabulary (subset of ColDP `NomRelType` with `homotypic = true`):
`basionym`, `homotypic`, `spelling correction`, `based on`, `replacement name`, `superfluous`.
`later homonym` and the other heterotypic types are **not** homotypic and never group.

### Dropping `basionym_id`

New migration `V34__drop_basionym_id.sql`:

```sql
ALTER TABLE name_usage DROP COLUMN basionym_id CASCADE;
```

V3 declares the compound `(project_id, basionym_id) REFERENCES name_usage(project_id, id) ON
DELETE SET NULL` as an inline (Postgres auto-named) constraint, so `DROP COLUMN … CASCADE` drops
the dependent FK with it — no separate `DROP CONSTRAINT` needed.

Ripple — remove every `basionymId` reference:

- `NameUsage` POJO: drop the field + accessors.
- `NameUsageMapper`: drop `basionym_id` from the insert column list/values, the update
  statement, and every `SELECT` column list. `updateHierarchy(projectId, id, parentId,
  basionymId, userId)` loses its `basionymId` parameter → `updateHierarchy(projectId, id,
  parentId, userId)` (sets `parent_id` only).
- Grep `merge`/`match`/`bulk` for stray `basionym_id`/`basionymId` and clean up.
- `NameUsageResponse` and the frontend `NameUsage` type already omit it — no change.

### Import (`ImportRunService`)

Pass 1 already inserts every usage with `parent_id`/`basionym_id` NULL. After dropping the
column, Pass 1 simply stops setting `basionym_id`. Pass 2 (every usage now exists):

- Resolve `parent_id` via `updateHierarchy` (parent only).
- Resolve the row's `basionymID` source id to a new usage id. If present and it differs from the
  row's own id, **create a `basionym` name_relation** (usage → basionym) via `NameRelationMapper`
  — *unless* a `basionym` relation already exists for that exact `(usage_id, related_usage_id)`
  pair (an explicit `NameRelation.tsv` row may have declared it). Dedupe by querying existing
  relations of type `basionym` for the usage before inserting.
- Unresolvable `basionymID` continues to produce the existing `ImportIssue` rather than failing.

Relations imported straight from `NameRelation.tsv` keep flowing through the existing child-entity
import path unchanged; only the `basionymID`-column channel is redirected.

### Export (`NameUsageColdpWriter` / `ChildColdpWriter`)

Basionym is exported **only as a `name_relation` row**, never via the `basionymID` Name column:

- `NameUsageColdpWriter`: stop emitting `ColdpTerm.basionymID` (the source column is dropped, and
  we no longer represent basionym on the Name row). The column is left empty in `NameUsage.tsv`.
- `ChildColdpWriter` (`NameRelation.tsv`): emit **all** name relations, including `basionym` —
  which now flows through the existing child-entity export path with **no special-casing**. This
  is the natural consequence of `name_relation` being the single source of truth: a basionym is
  just another relation.

Round-trip is lossless: an inbound ColDP `basionymID` **or** `NameRelation.tsv` basionym row →
`basionym` relation → exported `NameRelation.tsv` basionym row. No bulk export helper is needed —
the writer already loads and emits `name_relation` rows.

## Detection engine (backend)

New package `org.catalogueoflife.editor.name.homotypy`.

`HomotypyDetector` — pure, stateless, no DB, no parser:

- **Input:** the accepted usage + its synonyms (misapplied excluded), as `NameUsage` objects
  carrying the parsed fields already stored (`genus`, `specificEpithet`, `infraspecificEpithet`,
  `uninomial`, `combinationAuthorship`, `combinationAuthorshipYear`, `basionymAuthorship`,
  `basionymAuthorshipYear`).
- **Bucket** by normalized terminal epithet: infraspecific epithet if present, else specific
  epithet, else uninomial — each passed through `SciNameNormalizer.normalizeEpithet`.
- **Cluster** within a bucket by *basionym-or-combination* author team + year:
  - The comparison author is the basionym authorship if the name has parenthetical authorship,
    else the combination authorship. Surnames normalized via `AuthorshipNormalizer` (handles
    the alias table + transliteration); compare as normalized surname sets (order-insensitive).
  - Year: equal, or either side missing (missing matches any). No fuzzy year window in v1
    (a plain equality/most-present rule; the curator fixes edge cases). *(Rationale: CLB's
    5-year fuzz exists for machine consolidation of noisy aggregated data; a curator confirming
    each group does not need it, and exact-or-missing keeps the primitive simple and
    predictable. Revisit in Side 2 if cross-dataset noise warrants it.)*
  - A name **without** parenthetical basionym authorship is the bucket's **basionym**; names
    **with** it are **recombinations**. Ex-author-only matches (a name whose ex-author equals the
    group author) are proposed as `based on` rather than `basionym`.
- **Emit** `ProposedGroup`s. The bucket whose epithet equals the accepted name's terminal epithet
  is the **accepted's homotypic group** (its members are recombinations `≡` to the accepted).
  Every other non-singleton bucket, and every singleton, is a heterotypic group (a basionym with
  zero-or-more recombinations). For a multi-name bucket with no discernible basionym, members are
  chained as peer `homotypic` relations.
- **Respect existing relations:** each proposed relation is flagged `alreadyExists` when a
  matching `name_relation` row is already present, so the confirm UI shows what is new vs.
  established and never proposes clobbering a curator's manual grouping.

Output DTOs (`homotypy/dto/`): `ProposedRelation(usageId, relatedUsageId, type, alreadyExists)`
and `HomotypyProposal(List<ProposedGroup> groups)` where a `ProposedGroup` names its basionym (or
null) + ordered members + their proposed relations. Nothing is persisted here.

`HomotypyService` orchestrates: loads the accepted + synonyms + existing relations, runs the
detector, and (on apply) writes confirmed relations.

## API

Added to the usage controller (`/api/projects/{pid}/usages/{id}`), owner/editor for writes:

- `GET …/{id}/homotypic/detect` → `HomotypyProposal`. Read-only preview; any member may call.
- `POST …/{id}/homotypic/apply` — body `{ relations: [{ usageId, relatedUsageId, type }] }`.
  Creates the confirmed `name_relation` rows (idempotent: skips a row that already exists for the
  same `(usage_id, related_usage_id, type)`). Owner/editor only. Returns the refreshed synonymy.
- `GET …/{id}/synonymy` → the **grouped/nested** synonymy for display. Reads stored
  `name_relation` rows and computes the homotypic transitive closure with a depth-guarded
  recursive CTE (mirroring `TaxonDao.getSynonymy`), returning:
  - `homotypic`: synonyms homotypically related (transitively) to the accepted name, basionym
    first then recombinations.
  - `heterotypicGroups`: `List<List<SimpleSynonym>>` — each a mutually-homotypic cluster
    (basionym first), groups ordered by basionym year then name.
  - `misapplied`: misapplied-status synonyms.

  Each entry carries the display fields (`id`, `scientificName`, `authorship`, `rank`, `status`,
  formatted name, and its relation type to its group's basionym so the UI can pick `≡`/`=`).

Manual relation CRUD is unchanged (`NameRelationController` + `NameRelationsTab`).

### Transitive closure

A recursive CTE over `name_relation` filtered to the homotypic types, walked in both directions
(`usage_id ↔ related_usage_id`), path-guarded, `depth < 100` (Blixa datasets are small; CLB uses
25). Lives in `SynonymyMapper` (new). Two closures are computed: the accepted name's homotypic set
(→ `homotypic`), and, for the remaining synonyms, per-name closures clustered into
`heterotypicGroups` (a synonym already placed in a prior cluster is skipped).

## Rendering (frontend)

`frontend/src/tree/Synonymy.tsx` replaces `SynonymList` for **accepted** usages on `TaxonDetail`
(synonym/misapplied usages keep the existing simple "accepted name(s)" list — that path is
untouched):

- Fetches `GET …/{id}/synonymy` via `api/usages.ts` (`getSynonymy`).
- Renders the accepted's homotypic recombinations first (`≡` prefix), then each heterotypic group
  (basionym `=`, its recombinations nested/indented with `≡`), then misapplied. Layout matches the
  portal screenshot. Per-row unlink stays available to owners/editors (removes the relevant
  `name_relation` / `synonym_accepted` link).
- A **"Group synonyms"** button (owner/editor) opens `HomotypicGroupModal`: calls `detect`, shows
  proposed groups with new-vs-existing relations flagged, lets the curator toggle membership and
  the basionym/relation-type choice, then `apply`. On success, invalidates the synonymy +
  usage queries.

`api/usages.ts` gains `getSynonymy`, `detectHomotypic`, `applyHomotypic` and the matching types.

## Testing (TDD, RED first)

**Backend**

- `HomotypyDetectorTest` (pure unit, no Spring): `Poa annua`-style fixtures —
  - recombinations (`Ochlopoa annua (L.) H.Scholz`) group to the accepted basionym (`Poa annua`
    L.) as `basionym`;
  - different epithets (`Aira pumila`, `Festuca tenuiculmis`, `Poa aestivalis`) stay as separate
    heterotypic groups;
  - a recombination + its own basionym in a heterotypic bucket (`Catabrosa pumila` (Pursh) ↔
    `Aira pumila` Pursh) group together, basionym designated correctly;
  - missing year on one side still matches; author-alias variants match via `AuthorshipNormalizer`;
  - ex-author-only match → `based on`;
  - a bucket with no basionym → peer `homotypic` chain.
- `HomotypyApiIT`: `detect` returns groups with `alreadyExists` flags; `apply` persists relations
  and is idempotent; `synonymy` partitions homotypic/heterotypic/misapplied via stored relations
  incl. transitive closure; authz (viewer may detect + read synonymy, may not apply; non-member
  404).
- `ImportBasionymRelationIT` (or an assertion added to the existing import IT): a ColDP package
  with `basionymID` yields a `basionym` name_relation (not a column), deduped against an explicit
  `NameRelation.tsv` basionym for the same pair; export emits that basionym **as a
  `NameRelation.tsv` row** and leaves the `NameUsage.tsv` `basionymID` column empty (round-trip
  lossless via the relation).

**Frontend**

- `Synonymy.test.tsx`: renders a mocked synonymy payload nested correctly (accepted recombinations
  `≡`, heterotypic groups `=` with nested `≡`, misapplied last).
- `HomotypicGroupModal.test.tsx`: `detect` shows proposed groups; toggling membership + Apply posts
  the confirmed relations; existing relations shown as already-grouped.

## Verification

- Backend TDD: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -Dtest=none
  -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=HomotypyApiIT -DfailIfNoTests=false verify`
  (and the detector unit test via `-Dtest=HomotypyDetectorTest ... test`); RED before impl, GREEN
  after; then a full `./mvnw clean verify` before commit (schema + record-arity changes).
- Frontend: `cd frontend && npx vitest run src/tree/Synonymy src/tree/HomotypicGroupModal` +
  `npx tsc -b` + `npm run build`.
- Manual: import a small ColDP with basionym links → open the accepted taxon → confirm the nested
  synonymy renders; run "Group synonyms" on a taxon with un-grouped recombinations → confirm →
  see the nesting update; export → confirm the basionym appears as a `NameRelation.tsv` row and
  the `NameUsage.tsv` `basionymID` column is empty.
- Commit per logical unit (migration + column drop, import/export redirect, detection engine +
  API, frontend), directly to `main` per repo convention. Mark the backlog "Homotypic grouping"
  item's Side 1 shipped.

## Out of scope (Side 1)

- Cross-dataset / subtree consolidation of accepted names (**Side 2**).
- Fuzzy year windows and orthographic-variant (`spelling correction`) auto-detection beyond exact
  epithet normalization — curators add these via the manual relations tab for now.
- Any change to the synonym/misapplied usage view (only the accepted-usage synonymy is nested).
