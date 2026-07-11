# Supervised project merge — design

**Status:** approved (brainstorm) · **Date:** 2026-07-11

## Context & goal

The ColDP import always loads an archive into a **new** project — a staging store. This feature lets a
curator **merge a staging (source) project into an existing target project** under supervision: names
**and references** are matched against the target, the curator reviews the impact and the full mapping,
overrides anything, chooses how matches reconcile, and applies. Matched records keep the target's
**stable ids**; new records are added and slotted into the target classification.

The matching + merge core is deliberately **generic** so two later features reuse it (see Reuse):
**intra-project duplicate detection** and **direct CLB taxon import**.

## Use cases (one general engine)

- **Fold in an external list** — add new names, link overlaps to existing accepted names.
- **Ingest an updated source** — reconcile/update matched names, add new ones.
- **Fast full-import** — an empty (or opt-in) target: every source record is NEW, so it is the same
  apply with matching skipped (a degenerate plan).
- **Cherry-pick** (a subtree/selection) is a later refinement, not the first cut.

## Non-goals (this spec)

- **Intra-project dedup** and **direct CLB import** are *designed-for follow-ons*, not built here — but
  the matcher and the merge-one-record operation are factored so they reuse them.
- No automatic 3-way field merge / no conflict resolution beyond the **global mode** + per-record override.
- No cross-project transfer of change-history/audit rows.

## Matching (local, homonym-safe) — names and references are both first-class

References are **first-class citizens**, not a by-product of name merging: the curated reference library is
reconciled in its own right. **Every** source reference is matched — including references no imported name
points at — with its own metrics, its own review table, and its own overrides, at equal standing with names.

A single shared pipeline runs per entity type: for each SOURCE record, find TARGET candidates, assign a
**category**, let the curator review/override, then apply with an **id-remap** (`sourceId → targetId | NEW`).
Two peer entity types run through it; a re-run against the same target is deterministic.

### Name-usages
Canonical key = GBIF name-parser `canonicalComplete` (author-stripped canonical) + `rank`.
- **MATCHED** (auto) — canonical key equal **and** authorship compatible (normalized-equal, or one side blank).
- **POSSIBLE_HOMONYM** — canonical key equal but **conflicting** authorship (a real homonym is a *different*
  name) → review.
- **POSSIBLE_FUZZY** — pg_trgm `similarity(scientific_name)` above a threshold → review.
- **NEW** — no candidate.
Only MATCHED auto-applies; POSSIBLE_* default to NEW but are surfaced **prominently** (so near-duplicates
aren't silently added).

### References (the parallel routine the design must include)
Match key = normalized DOI, else normalized citation string (case/whitespace/punctuation folded).
- **MATCHED** (auto) — same normalized DOI, **or** same normalized citation.
- **POSSIBLE** — pg_trgm-similar citation above a threshold, no DOI match → review.
- **NEW** — no candidate.
Same review/override/apply-with-id-remap as names.

*Factoring:* names and references share the plan shape (`Candidate{sourceId, category, targetId?, score}`),
the override layer, and the id-remap that apply consumes — implemented per entity, not force-fit into one
class where awkward. Intra-project dedup instantiates the same shape over a single project.

## Dry-run plan (nothing applied)

An async **`merge_run`** computes, writing **no** target data — references and names are each matched in
their own right (the refs-before-names ordering is only for pointer remap at apply time, not a status
hierarchy):
1. the **reference mapping** (all source references),
2. the **name mapping**,
3. **impact metrics** — per entity type, with equal prominence: names → new / matched / POSSIBLE_HOMONYM /
   POSSIBLE_FUZZY; references → new / matched / POSSIBLE; plus derived counts: new-accepted vs new-synonym,
   matched names that *would change* under each mode, and **unanchored new branches** (new names with no
   matched ancestor).
The plan is persisted as JSONB on the run. Status: `RUNNING` (computing) → `PLANNED` (awaiting review) →
`APPLYING` → `DONE` | `FAILED`.

## Review & override

The UI shows the **metrics** first. On demand the curator opens the **full mapping** — **peer tabs of equal
prominence, one for names and one for references** — each listing every source record with its category,
matched target (**including auto-matches**), and score. They can **override** any row:
- confirm a POSSIBLE_* → MATCHED (to the suggested or a chosen target),
- reject a MATCHED → NEW,
- re-point a match to a different target record (a picker; names → a target-usage picker, references → a
  target-reference picker).
Overrides are saved back onto the plan (`PUT …/overrides`). The curator then picks the **global mode** and
applies.

## Apply (id-stable, mode-governed)

Global **mode** governs matched records: **(a) overwrite** (source wins) · **(b) fill-gaps** (keep target
scalars, fill blanks, add missing relations) · **(c) new-only** (matched records untouched; only NEW added).

Order (mirrors import's references-before-names):
1. **References** — apply the reference mapping (all source refs): matched refs **keep the target id** (mode
   governs the matched ref's scalar reconciliation) and record the source id as a provenance `src:<id>` CURIE
   (default on, same as names); NEW refs are inserted with fresh target ids. Build `refIdMap : sourceRefId → targetRefId`.
2. **Name-usages** —
   - **Matched → keep the TARGET id** (stable-identifiers principle). Reconcile per mode (overwrite: source
     scalars win; fill: fill blank target scalars + add missing relations; new-only: skip). Record the source
     id as a provenance `src:<id>` CURIE (**default on**).
   - **New → allocate a target id**, insert; its `publishedInReferenceId`/`referenceId[]` remap through `refIdMap`.
   - **Classification insertion** — new accepted names attach at their **nearest matched ancestor** in the
     target tree (inserted **top-down**: a new species under a matched genus, a new genus under a matched
     family, …). A new branch with **no** matched ancestor → a new **root**, flagged in the plan.
   - **Synonyms** — a source synonym of a matched accepted → added to that target accepted if not already
     present; a matched synonym → per mode.
   - **Child entities** (distribution/vernacular/media/estimate/property/type-material/name-relation):
     fill = add missing (dedup by content), overwrite = replace that name's set of each present type,
     new-only = skip. Child `referenceID` remaps through `refIdMap`; child `taxonID`/`nameID` through the
     name id-remap.
3. **Validate** — revalidate the target project after apply (best-effort, post-commit), like import.

The whole apply is **one transaction** (rollback on fatal error; the FAILED status write stays outside it,
as in import); per-record non-fatal problems → `merge_run.issues`.

## Data model & API

- **`merge_run`** table: `id, source_project_id, target_project_id, status, mode (null until apply),
  plan JSONB, metrics JSONB, issues JSONB, started_at, planned_at, finished_at, error`. Partial unique index
  = one active run per **target** project; startup stale-sweep; single-thread async via `@Lazy self` —
  mirrors `import_run`/`col_match_run`.
- **Endpoints** (target-project-scoped; owner/editor):
  - `POST /api/projects/{targetId}/merge?source={sourceId}` → 202 (computes the plan, async).
  - `GET /api/projects/{targetId}/merge/{runId}` → plan summary + metrics.
  - `GET …/merge/{runId}/mapping?entity=name|reference&category=…&page=…` → paged full mapping for review.
  - `PUT …/merge/{runId}/overrides` → save overrides (rejected before status PLANNED / after APPLYING).
  - `POST …/merge/{runId}/apply` (body `{mode}`) → applies (async), APPLYING→DONE.
  - `GET …/merge/latest` → latest run for the target.

## Reuse (designed-for follow-ons, not built here)

- **Intra-project dedup** instantiates the same matcher over ONE project (source == target): canonical-equal
  and trigram-similar pairs → POSSIBLE, reviewed, then the same merge-one-usage op (keep the chosen/older id).
- **Direct CLB import** (backlog) fetches selected taxa from the CLB API into a transient source set, then
  runs the same plan → review → apply against the target — so a re-pull reconciles rather than duplicates.

## Frontend

A **Merge** action (target Project page): pick a source (staging) project → start → poll → **impact metrics**
panel → "Review mapping" opens the names/references tables (filter by category, override, re-point picker) →
pick mode → **Apply** → poll → summary + issues + a link to the reconciled target. Reuses the run poll/summary
patterns from import/col-match.

## Testing

- Matcher unit tests: canonical-key equality, author-compatibility, homonym split, trigram threshold;
  reference DOI/citation normalization + match.
- Plan IT: import a fixture archive into a source project, compute a plan against a target with known overlaps
  → assert categories + metrics (names and references).
- Apply ITs, one per mode: matched names keep target ids; new names added + slotted at nearest matched ancestor;
  refs remapped through `refIdMap`; synonyms/children per mode; provenance `src:` CURIEs; the empty-target fast
  path (all NEW).
- Override IT: reject an auto-match / confirm a possible → apply reflects it.
- Guard/lifecycle ITs (one active run per target, startup sweep) mirrored from import.
- Frontend: metrics + mapping review + override + apply (MSW).

## Build phases (each shippable)

1. **Matching engine + plan** — the shared matcher (names + references), the `merge_run` job computing the
   plan + metrics (no apply). API: start + get plan + paged mapping. IT: plan categories/metrics.
2. **Apply** — mode-governed, id-stable apply (refs → names → children; classification insertion; provenance;
   validate) + overrides. Apply ITs per mode + override IT.
3. **Review UI** — metrics + mapping tables + override + mode + apply, wired on the target Project page.

## Caveats / future

- Very large source projects: the plan JSONB + one-transaction apply are memory/lock-heavy; batching is a later
  optimization (note the limit).
- Author-compatibility + citation normalization are heuristic (GBIF parser / string folding); borderline cases
  fall to POSSIBLE_* and are **reviewed**, never silently merged.
- Cherry-pick (subtree selection) and a chosen **mount point** for unanchored new branches are deferred
  refinements (unanchored → new roots for now).
- Intra-project dedup and direct CLB import reuse this engine — each its own spec.
