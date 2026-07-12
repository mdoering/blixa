# Intra-project reconciliation (dedupe / merge) — design

**Status:** DRAFT — designed autonomously while the owner was offline; **the merge/conflict
semantics (esp. §Merge decisions) want the owner's confirmation before the destructive
apply is implemented.** Detection + review UI are non-destructive and safe to build now.
**Date:** 2026-07-12

## Goal

Within a **single project**, surface likely-duplicate **names** and **references**, and let a
curator merge each duplicate group into one survivor — always behind **explicit per-group manual
review** (nothing auto-merges). This is the intra-project counterpart to the existing project→project
**merge** feature, reusing its matchers.

## What exists to reuse

- `merge/NameMatcher.canonicalKey(NameUsage)` — an author-stripped, rank-qualified key; grouping the
  project's own usages by it yields exact-duplicate clusters. Plus `authorCompatible` + a trigram
  fuzzy fallback (for a later phase).
- `merge/ReferenceMatcher` — DOI-exact, normalized-citation-exact, and trigram-fuzzy citation
  (threshold `coldp.merge.citation-similarity`, default 0.9) matching.
- `merge/` async-job pattern (`MergeService`/`MergeAsyncConfig`/`MergeRunRecovery`, single-thread
  executor, `@Lazy self`, status run rows, startup stale-sweep) — the template for the scan job.
- The FK repoint targets (see §Merge mechanics) are the same columns the merge apply already knows.

## Scope (MVP) and decisions made autonomously

Flagged for the owner to confirm:

1. **MVP detects exact clusters only** — names sharing a `canonicalKey`; references sharing a DOI or
   an exact normalized citation. **Fuzzy** candidates (trigram) are a **phase-3** add (higher false-
   positive rate → needs the review UI mature first).
2. **Suggested survivor** = the member with the most inbound links (children + synonym links +
   child-entity rows + reference uses), tie-broken by lowest id (stable, oldest-wins). The curator can
   override per group.
3. **Merge conflict semantics = "survivor scalar wins, dependents move."** The survivor keeps its own
   scalar fields (authorship, references, publishedIn, taxon-info, status, etc.). Every *dependent*
   of a non-survivor — accepted children, synonym links, basionym links, child entities, reference
   uses — is **repointed to the survivor**; then the non-survivor row is deleted. **No field-level
   merge** in MVP (predictable + reversible-in-principle; a field-merge/"fill gaps" mode is phase-3).
4. **Dismiss is per-scan** — dismissing a cluster hides it from the current run; a re-scan re-surfaces
   it. A persistent "confirmed not a duplicate" allowlist is phase-3.
5. **Scan is an async persisted run** (mirrors merge) — a `reconcile_run` + its clusters, so a large
   project's grouping doesn't block and the review survives navigation. After merges, the curator
   re-scans.

## Architecture

Two independent reconcilers — **names** and **references** — of identical shape. A `reconcile_run`
(scoped to a project + an `entity_type` of `NAME` or `REFERENCE`) is started by an owner/editor,
runs async, and produces persisted **clusters**.

### Data model (migration `V23`)

- `reconcile_run(id, project_id FK cascade, entity_type [NAME|REFERENCE], status
  [RUNNING|DONE|FAILED], cluster_count, created_by, started_at, finished_at, error)`.
- `reconcile_cluster(id, run_id FK cascade, key [the canonical/DOI/citation key], member_ids
  [int[]], suggested_survivor_id, status [OPEN|MERGED|DISMISSED])`. `member_ids` is a Postgres
  `integer[]` (the usage or reference ids in the cluster).

### Detection (async scan job)

- `POST /api/projects/{id}/reconcile {entityType}` (owner/editor) → inserts a RUNNING run, hands off
  to `@Async` (dedicated single-thread executor, `@Lazy self`, `MergeRunRecovery`-style startup
  sweep). The job:
  - **NAME:** load the project's usages (id + atomized name parts + status + rank), compute
    `NameMatcher.canonicalKey` for each, group; every key with ≥2 members → a cluster with a
    suggested survivor (§decision 2).
  - **REFERENCE:** group references by normalized DOI, then (for those without a shared DOI) by
    normalized citation; ≥2 members → a cluster. (Reuse `ReferenceMatcher`'s normalization.)
  - Persist clusters; finish the run.
- `GET /api/projects/{id}/reconcile/latest?entityType=` and `GET …/reconcile/{runId}` → poll/read
  (mirrors export/merge run polling). `GET …/reconcile/{runId}/clusters?status=OPEN&limit&offset` →
  paged clusters, each with its members' display fields (name/authorship/rank/status, or
  citation/doi) resolved for the review UI.

### Review + merge (manual, transactional, owner/editor)

- The UI (a new "Reconcile" project view, or a section) lists OPEN clusters for the latest run, per
  entity type. Each cluster shows its members side by side with distinguishing detail and a
  radio-selected survivor (defaulted per decision 2). Per-cluster actions:
  - **Merge** `POST …/reconcile/clusters/{clusterId}/merge {survivorId}` → applies the merge in one
    transaction (project tree advisory-locked), marks the cluster `MERGED`.
  - **Dismiss** `POST …/reconcile/clusters/{clusterId}/dismiss` → marks `DISMISSED` (per decision 4).
- The merge validates that `survivorId` and the members belong to the cluster/project, that the
  cluster is still `OPEN`, and (for names) re-derives the current dependents under the lock
  (avoiding a TOCTOU on concurrent edits).

### Merge mechanics (the destructive core — the part wanting owner sign-off)

**Merge a NAME cluster** into survivor `S`, for each non-survivor `D` (all in one `@Transactional`,
`tree.lockProject` first):
- `name_usage.parent_id = S where parent_id = D` (D's accepted children reparent to S).
- `name_usage.basionym_id = S where basionym_id = D`.
- `synonym_accepted`: `synonym_id = S where synonym_id = D`, and `accepted_id = S where accepted_id =
  D`; then **dedup** (a `(project_id, synonym_id, accepted_id)` may now collide — delete the
  duplicates, and drop any self-link `synonym_id = accepted_id`).
- Every child-entity table with `usage_id` (vernacular, distribution, media, type_material,
  name_relation, property, estimate) and `taxon_info`: `usage_id = S where usage_id = D`. (If S is a
  synonym, taxon_info/children semantics still hold since S is the survivor; a merge that would make
  a synonym own accepted children is rejected — the survivor should be the accepted one, which
  decision 2's link-count default tends to pick.)
- `name_usage`-level references on D (published_in, reference uses) are dropped with D (S keeps its
  own) — decision 3.
- Delete `D`. Audit a `merge` change for S and a delete for D.

**Merge a REFERENCE cluster** into survivor `S`, for each non-survivor `D`:
- Repoint every `reference_id` / `published_in_reference_id` FK from `D` to `S`: `name_usage`
  (published-in + name-reference), `vernacular`, `distribution`, `media`, `type_material`,
  `name_relation`, `property`, `estimate`, `synonym_accepted`, `author` — every table the FK-map
  grep found. Dedup any resulting duplicate m2m rows.
- Delete `D`. Audit.

**Reversibility note:** merges are not undoable (they delete rows). The manual-review gate is the
safety; the change log records what happened. A future "dry-run impact preview" (like the project
merge's impact metrics) is a strong phase-2 candidate and is recommended before this ships widely.

## Testing strategy

- **NAME scan:** seed exact-duplicate usages (same canonical, different ids/authorship) → a cluster
  with the right members + suggested survivor; non-duplicates don't cluster.
- **NAME merge:** a survivor + a dup with children, synonyms (both directions), a basionym link, and
  a vernacular/distribution → after merge, all repoint to the survivor, the synonym_accepted dedup is
  correct (no collisions, no self-links), the dup is gone, and no orphaned child rows remain.
- **REFERENCE scan + merge:** duplicate refs by DOI and by citation → cluster; merge repoints all
  `reference_id` FKs and deletes the dup; a usage that cited the dup now cites the survivor.
- **Authz:** scan/merge/dismiss are owner/editor (viewer 403); cluster/survivor validation (foreign
  cluster/member → 400/404); cluster must be OPEN to merge.
- **Recovery:** stale RUNNING reconcile_run → FAILED at startup.
- **Frontend:** the Reconcile view renders clusters, lets the user pick a survivor and merge/dismiss,
  and refreshes; empty state ("no duplicates found").

## Decomposition (phases)

1. **Name reconciliation** — migration, `reconcile_run`/`cluster`, async scan (canonicalKey grouping),
   the name-merge apply, endpoints, recovery, and the Reconcile UI (names tab).
2. **Reference reconciliation** — the same run/cluster machinery for references (DOI/citation
   grouping + reference-merge apply), the references tab.
3. **Future:** fuzzy candidates; a persistent "not a duplicate" allowlist; a dry-run impact preview
   before merge; a field-level "fill gaps" merge mode.

## Out of scope

- Cross-project dedup (that is the existing project→project merge feature).
- Automatic merging (everything is behind per-cluster manual review).
- Undo/rollback of an applied merge (mitigated by review + the change log; a dry-run preview is the
  planned safety upgrade).
