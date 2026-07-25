# Classification bar in the taxon edit form

**Date:** 2026-07-25
**Status:** approved (prescriptive request from Markus)

## Goal

Show the focal taxon's classification as a single compact line inside the editing form
(`TaxonDetail`), so a curator always sees where the name sits without switching to the tree.

## Requirements (from the request)

- One line, placed right above the tabs.
- Show the **closest** ancestors; collapse the higher ones into a leading `…`.
- Delimiter is `>`.
- Every entry is a **link** that navigates the form to that ancestor.
- The **last entry is the direct parent** and carries a small **change icon** that opens the
  move flow:
  - accepted focal → reparent (existing `MoveNameModal`),
  - synonym/misapplied focal → change its accepted name (new `ChangeAcceptedModal`).

## Data

No backend change. `GET /tree/path/{id}` (`getPath`) already returns the root-first accepted
ancestor chain **including** the anchor as the last entry. We fetch the path of the focal
taxon's *parent*, so the returned chain ends exactly at the direct parent:

- accepted / unassessed focal → anchor = `usage.parentId`
- synonym / misapplied focal → anchor = `usage.acceptedParentIds[0]` (its accepted name is its
  "parent"; pro-parte replaces this primary link)

If the anchor is null (an accepted root, or an unlinked synonym) the bar renders nothing.

`findPath`'s base case is `status = 'ACCEPTED'`, and every anchor above is accepted, so the query
is always valid for these anchors.

## Components

### `ClassificationBar` (new, `tree/`)

Props: `pid`, `usage: NameUsage`, `canEdit: boolean`, `onNavigate?: (id) => void`.

- Resolves the anchor id (above); `useQuery(['treePath', pid, anchorId], getPath)` (same key as
  before, cache shared).
- Renders entries joined by `>`; keeps the last `MAX_CROMBS = 4` (closest) and prefixes `…` when
  the chain is longer. The `…` is plain text (hidden ancestors reachable by navigating up).
- Each entry is an `Anchor` calling `onNavigate(id)`; with no `onNavigate` it's plain `Text`.
- After the last entry (direct parent), when `canEdit` and the focal is accepted **or**
  synonym/misapplied, a small change `ActionIcon` (`IconArrowsExchange`) opens the right modal.
  Unassessed focal: bar shows, no change icon (the backend reparent is accepted-only).

### `ChangeAcceptedModal` (new, `tree/`)

Replaces a synonym's accepted name. Mirrors `LinkAcceptedModal`'s tree picker, but on confirm it
**links the new** accepted and **unlinks the current** one (`linkSynonym` then `unlinkSynonym`),
guarded so picking the same target is a no-op. Invalidates the usage + accepted/synonym queries.

## Wiring

- `TaxonDetail` gains an optional `onNavigate` prop and renders `<ClassificationBar>` just above
  `<Tabs>`.
- `TreePage` and `NameSearchPage` pass their `setSelectedId` as `onNavigate`.
- The now-redundant standalone `<Breadcrumb>` in `TreePage` (and `Breadcrumb.tsx`) is removed —
  the form owns the classification line now.

## Tests (vitest + MSW)

- `ClassificationBar`: truncates with `…` past MAX; entries call `onNavigate`; change icon opens
  `MoveNameModal` for accepted and `ChangeAcceptedModal` for a synonym; hidden when anchor null;
  no change icon when `!canEdit`.
- `ChangeAcceptedModal`: picking a new accepted calls link(new)+unlink(old); same-target no-op.
