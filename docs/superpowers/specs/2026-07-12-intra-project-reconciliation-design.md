# Merge names / references (user-selected) — design

**Status:** approved direction (owner re-scoped: the value is the merge mechanics, not detection).
**Date:** 2026-07-12

## Goal

Let an editor **merge 2+ names, or 2+ references, that they have selected** in the search tables into
a single survivor — the duplicate's dependents (children, synonyms, links, child-entities, reference
uses) repoint to the survivor and the duplicate is deleted. **Detection is left to the user** (they
find the duplicates via the search table + filters); this feature provides the **merge mechanics** and
a review step. No async scan, no auto-detection.

## Flow

1. In the **Names search table** (`NameSearchPage`) or the **References table** (`ReferencesPage`),
   the editor **multi-selects** rows (row checkboxes). With ≥2 selected, a **"Merge selected…"**
   action (toolbar button / context-menu entry) opens the merge modal.
2. The **merge modal** shows the selected records **sorted by id ascending (oldest / lowest id
   first)**, each with its **record identifier(s)** — the per-project numeric `id`, plus its
   `alternativeId` source CURIEs (e.g. `col:…`) when present — and its **associated-info counts** (how
   much is linked to it — so the editor can judge which to keep). A **survivor** is chosen via a
   radio, defaulted to the most-connected record (see §Survivor default). The editor can change the
   survivor, then confirms **"Merge N records into <survivor>"**.
3. The backend applies the merge in one transaction and the table refreshes (the merged rows are gone).

Nothing merges without this explicit selection + confirmation.

## Backend

Two parallel surfaces — **names** and **references** — under the existing project routes.

### Preview (associated counts + survivor suggestion)

- `POST /api/projects/{pid}/usages/merge/preview {ids:[...]}` → for each id (validated in-project),
  return `{ id, alternativeId, scientificName, authorship, rank, status, counts }` **ordered by id
  ascending (oldest first)**, where `counts` is:
  - `children` — accepted children (`name_usage.parent_id = id`),
  - `synonyms` — synonyms of it (`synonym_accepted.accepted_id = id`),
  - `acceptedOf` — accepted targets it is a synonym of (`synonym_accepted.synonym_id = id`),
  - `nameRelations`, `vernacular`, `distribution`, `media`, `typeMaterial`, `property`, `estimate`
    (each child table where `usage_id = id`),
  - `basionymOf` (`name_usage.basionym_id = id`).
- `POST /api/projects/{pid}/references/merge/preview {ids:[...]}` → `{ id, alternativeId, citation,
  doi, counts }` **ordered by id ascending (oldest first)**, where `counts` is the number of rows
  pointing at the reference across every `reference_id` / `published_in_reference_id` column (usages
  published-in + name-reference, and each child-entity).

Preview endpoints are read-only (any member). Returned counts drive the modal's per-record summary and
the default survivor.

### Merge (transactional, owner/editor)

- `POST /api/projects/{pid}/usages/merge {survivorId, mergedIds:[...]}` — validates: all ids
  (survivor + merged) are distinct usages in the project; `survivorId ∈` the full selected set;
  ≥1 mergedId; and — because accepted children can only hang under an accepted usage — if any
  merged usage has accepted children (or the survivor already has some), the **survivor must be
  `ACCEPTED`** (else 400 "survivor must be an accepted name to receive children"). Then, in one
  `@Transactional` (project tree advisory-locked first), for each `D` in `mergedIds`:
  - `UPDATE name_usage SET parent_id = survivor WHERE parent_id = D` (reparent accepted children),
  - `UPDATE name_usage SET basionym_id = survivor WHERE basionym_id = D`,
  - `UPDATE synonym_accepted SET synonym_id = survivor WHERE synonym_id = D` and
    `SET accepted_id = survivor WHERE accepted_id = D`, then **dedup**: remove rows where
    `synonym_id = accepted_id` (self-link) and collapse duplicate `(project_id, synonym_id,
    accepted_id)` rows,
  - `UPDATE <child> SET usage_id = survivor WHERE usage_id = D` for `vernacular, distribution, media,
    type_material, name_relation, property, estimate` and `taxon_info` (dedup `taxon_info` — it is
    1-per-usage, so if the survivor already has a row, drop `D`'s),
  - `name_relation` also has a `related_usage_id` (the other end) — `UPDATE … SET related_usage_id =
    survivor WHERE related_usage_id = D`; then drop self-relations (`usage_id = related_usage_id`),
  - the survivor keeps its own scalar fields, `published_in`, and reference links (no field merge);
  - `DELETE FROM name_usage WHERE id = D`; audit a `merge` change on the survivor + a delete on `D`,
    and publish a `ValidationEvent` for the survivor.
- `POST /api/projects/{pid}/references/merge {survivorId, mergedIds:[...]}` — same validation shape;
  for each `D`: repoint every `reference_id` / `published_in_reference_id` FK
  (`name_usage` published-in + name-reference, `vernacular, distribution, media, type_material,
  name_relation, property, estimate, synonym_accepted, author`) from `D` to `survivor`, dedup any
  resulting duplicate m2m rows, `DELETE FROM reference WHERE id = D`, audit.

Both merges reuse the exact FK set the project→project **merge** feature already knows; the difference
is these repoint within one project rather than inserting new rows.

### Survivor default

The frontend defaults the survivor to the record with the **highest total associated count** (most
connected → least repointing), tie-broken by lowest id (stable). The editor can override.

## Frontend

- **Row selection** in `NameSearchPage`'s mantine-react-table (`enableRowSelection`) and in the
  `ReferencesPage` list. A selection toolbar shows "N selected — Merge…" (owner/editor only) when ≥2
  are selected.
- **`MergeRecordsModal`** (one component, parameterized by entity type): on open, calls the preview
  endpoint for the selected ids, renders a row per record **sorted by id ascending (oldest first)**,
  each showing its **id** (and `alternativeId` CURIEs when present), name/authorship/rank/status or
  citation/doi, and the counts as small badges; a survivor radio (defaulted per §Survivor default),
  and a "Merge N into <survivor>" button → the merge endpoint. On success: notify, clear selection,
  invalidate the search/tree/reference queries.
- Guard: the "Merge…" action is owner/editor only; the modal disables merge while <2 records or (for
  names) when the chosen survivor can't legally receive children (mirror the backend rule with a
  clear message).

## Testing

- **Name merge (backend IT):** a survivor + a merged name that has accepted children, synonyms (both
  directions), a basionym link, a name-relation (both ends), and a vernacular/distribution → after
  merge, all repoint to the survivor; the `synonym_accepted` dedup removes self-links + collisions;
  `taxon_info` collapses to one; the merged usage is gone with no orphaned child rows; the survivor's
  own name/fields are unchanged.
- **Name merge validation:** survivor not accepted but a merged name has children → 400; foreign/
  cross-project id → 400/404; <2 ids → 400; non-editor → 403.
- **Reference merge (backend IT):** two references, one cited by a usage (published-in) and one linked
  to a vernacular → merge repoints both to the survivor and deletes the merged ref; a usage that cited
  the merged ref now cites the survivor.
- **Preview (backend IT):** counts match the seeded associations for each id.
- **Frontend:** multi-select shows the Merge action at ≥2; the modal renders per-record counts +
  survivor radio, defaults to the most-connected, and the merge call clears selection + refreshes;
  the survivor-can't-receive-children case is blocked with a message.

## Decomposition (phases)

1. **Name merge** — preview + merge endpoints + mechanics, `NameSearchPage` multi-select +
   `MergeRecordsModal` (names).
2. **Reference merge** — the reference preview + merge endpoints + mechanics, `ReferencesPage`
   multi-select + the modal (references).
3. **Future (owner, later):** additional search **filters** to help find duplicates (e.g. same
   canonical name, same rank, near-duplicate citation) — the detection aids the owner asked to defer.

## Out of scope

- Automatic duplicate **detection** (the owner drives it via search + filters).
- Cross-project merge (that is the existing project→project merge feature).
- Field-level "fill gaps" merge (survivor's scalar fields win; only dependents move).
- Undo of an applied merge (mitigated by the explicit selection + review; the change log records it).
