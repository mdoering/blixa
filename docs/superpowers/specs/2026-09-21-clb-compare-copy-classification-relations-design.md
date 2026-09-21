# Copy classification and name relations from the CLB comparison — design

**Date:** 2026-09-21 · **Status:** implemented · **Follows:**
[2026-09-21-clb-compare-copy-design.md](2026-09-21-clb-compare-copy-design.md)

## Name relations

A `«` on each CLB-only relation of the focal name (and "« all"). Relations are compared by
`type + related scientific name` (our side's relation label carries no authorship).

The related name has to exist in the project. The copy resolves it, in order:

1. an existing usage in the project with the same scientific name — preferring a synonym of the
   focal name, then the same authorship, then any;
2. otherwise, if the related CLB usage is one of the focal taxon's CLB synonyms (the usual case for
   a basionym), that synonym is copied along with the relation (full record, as a synonym copy);
3. otherwise the relation is skipped and reported ("related name … not in project").

Implemented on the existing `clb-copy` endpoint (`nameRelationIds`, each `relatedUsageId|type`),
reusing the importer's deferred name-relation pass with the resolved ids pre-seeded.

## Classification

A "Wire into tree…" action on the Classification row opens a preview of the CLB higher
classification (root → direct parent), one line per rank:

| Status | Meaning |
|---|---|
| **exists** | an ACCEPTED usage with the same name and rank is in the project — used as-is (never moved or edited). Accepted only: an accepted taxon's parent must itself be accepted |
| **create** (checkbox) | not in the project; created as an ACCEPTED usage under the nearest ancestor above it, with the CLB id recorded as a scoped identifier (`col:` for COL, else `<datasetKey>:`) |

Only missing ranks **below the lowest existing match** can be created: existing taxa are never moved,
so anything created above one would stay an empty branch (shown as "not in tree"). Missing ranks are
pre-checked for the main Linnean ranks (kingdom, phylum, class, order, family,
genus) and unchecked otherwise, so COL's many intermediate ranks aren't created by default. The
preview states where the focal name will end up ("moves under Felidae", or "already there").

**Matching** walks root → parent: at each rank it prefers a candidate whose parent is the previously
resolved ancestor, else takes the single candidate (several unrelated candidates → the first, flagged
*ambiguous*). A candidate that is the focal name or one of its descendants is never used (no cycles).
Unchecked missing ranks are skipped: the chain continues from the last resolved ancestor.

**Apply** re-runs the same resolution server-side (the preview is advisory), creates the chosen
missing ancestors through `NameUsageService.create` (audited, validated) and re-parents the focal name
through `TreeService.move` (cycle-safe, optimistic lock), all in one transaction. Only tree nodes
(ACCEPTED / UNASSESSED) can be wired; the action is hidden for synonyms and for non-editors.

Endpoints:

- `GET  /api/projects/{pid}/usages/{id}/clb-classification?datasetKey=&taxonId=` → preview
- `POST /api/projects/{pid}/usages/{id}/clb-classification` `{datasetKey, taxonId, createClbIds[]}`
  → `{parentId, created, moved}`

## Out of scope

- Moving or renaming existing higher taxa that sit elsewhere in our tree.
- Fuzzy / authorship-aware matching of higher taxa (exact name + rank only).
