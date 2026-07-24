# Tree children paging ("Load more") — design

**Date:** 2026-07-24
**Status:** approved

## Problem

The classification tree is lazy per node (a node's children load when it's expanded), but the
frontend fetches `getChildren` without a limit, so the backend returns its **default page of 50**
and there is **no way to see the rest** — a node with >50 accepted children is silently truncated.
(Roots have the same 50-cap.) This is a correctness gap, not just a performance one, and it bites at
"Lepidoptera scale" where families/genera have hundreds–thousands of direct children.

Separately, the tree renders every loaded child as a real DOM row with no windowing. That's the
**virtualization** follow-up — out of scope here; this item is the paging fix, which composes with
virtualization later.

## Key fact: no backend change needed

Every `TreeNode` already carries **`childCount`** — the true total number of children (a `COUNT(*)`
scalar subquery that respects the `includeUnassessed` flag, same filter used to fetch the children).
The children/roots endpoints already accept `limit`/`offset`. So paging is entirely frontend.

## Design

- **Children (`TreeNodeRow`):** switch the children `useQuery` → `useInfiniteQuery`, pages of
  `PAGE = 50` by `offset`. `getNextPageParam`: next offset while `loaded < node.childCount`, else
  undefined. Render a **"Load N more"** row at the bottom of the expanded children (N =
  `childCount − loaded`), shown only while there are more; clicking fetches the next page and
  appends. Flatten `data.pages` for rendering.
- **Roots (`ClassificationTree`):** same `useInfiniteQuery`, but roots have no parent carrying a
  total, so `getNextPageParam` uses the **page-length heuristic** (last page length === PAGE →
  another page may exist; a short page ends it). A plain **"Load more"** row (no precise count).
  Roots > 50 is rare — this is a safety net.
- **Page size stays 50.** No backend change.
- **Invalidation:** create/delete/move already invalidate the children/roots query; `useInfiniteQuery`
  refetches all loaded pages, preserving how far the user had loaded.

### Ordering must be deterministic

Offset paging is only correct if the children/roots query has a stable, total `ORDER BY`. Confirm
`TreeMapper`'s roots/children queries order deterministically (e.g. by `ordinal, scientificName, id`);
if the order isn't fully deterministic, add `id` as a final tiebreaker (a tiny backend tweak) so
pages don't skip/duplicate.

## Testing

- Frontend: expanding a node with `childCount` > page size shows a "Load N more" row with the right
  remaining count; clicking it fetches `offset=50` and appends the next children; the row disappears
  once `loaded === childCount`; a node with `childCount ≤ 50` shows no row. Same for roots via the
  heuristic. (`react-query` `useInfiniteQuery` with MSW returning successive pages.)

## Out of scope

- Virtualization / windowing of rendered rows (separate follow-up).
- Server-returned total for roots (the page-length heuristic suffices; roots > 50 is rare).
- Search-within-siblings / jump-to-child (a different feature).
