# Collapsible left pane — design

**Date:** 2026-07-22

## Problem

The Tree page and the Names search page are both two-pane split screens: a left
pane (the classification tree, or the flat search table) and a right pane holding
the `TaxonDetail` **form**. The form is often the pane the curator wants more room
for, but its width is fixed by the Mantine `<Grid>` spans (tree 5 / form 7 on the
Tree page; table 7 / form 5 on the Names page). There is no way to give the form
more horizontal space.

## Goal

Let the user collapse the left pane so the form expands to the full width, and
restore it again. Remember the choice per page across reloads.

Chosen interaction (from brainstorming): a **collapse toggle** — a single button
that fully hides/shows the left pane. Not a draggable resizer.

## Design

### `CollapsibleSplit` component

A small reusable component in `frontend/src/components/CollapsibleSplit.tsx`
replaces the raw `<Grid>` on both pages.

Props:

- `left: ReactNode` — the collapsible pane (tree / table)
- `right: ReactNode` — the form pane (`TaxonDetail`), always visible
- `leftPercent: number` — the left pane's width when expanded, as a percentage.
  Tree page uses `~42` (today's 5/12); Names page uses `~58` (today's 7/12).
- `storageKey: string` — localStorage key for the collapsed flag; distinct per
  page so collapsing one does not collapse the other.
- `leftStyle?: CSSProperties` — passthrough so the tree keeps its existing
  `maxHeight: '75vh'; overflowY: 'auto'` scroll container.

Layout: flexbox (not Grid), so the panes flex cleanly at any width.

- **Expanded:** `[ left (flex 0 0 leftPercent%) ][ rail ◀ ][ right (flex 1) ]`
- **Collapsed:** `[ rail ▶ ][ right (flex 1) ]` — the left pane is unmounted.

Gap between panes matches the old `gutter="md"`.

### Toggle

A slim, always-present vertical rail (~24px wide) at the pane boundary holding one
Mantine `ActionIcon`:

- expanded → chevron-left icon, `aria-label="Collapse panel"`, collapses on click
- collapsed → chevron-right icon, `aria-label="Expand panel"`, restores on click

The rail is present in both states so the control is always reachable — in
particular, a collapsed tree can always be reopened to pick another taxon.

### Persistence

The collapsed boolean is read from `localStorage[storageKey]` on mount (default
`false` = expanded) and written back whenever it changes. Keys:

- Tree page: `coldp:split:tree:collapsed`
- Names page: `coldp:split:names:collapsed`

Malformed / missing values fall back to expanded.

### Page refactors

- `TreePage.tsx`: replace the `<Grid>` block with
  `<CollapsibleSplit leftPercent={42} storageKey="coldp:split:tree:collapsed"
  leftStyle={{ maxHeight: '75vh', overflowY: 'auto' }} left={<ClassificationTree …/>}
  right={<>…Breadcrumb + TaxonDetail…</>} />`. The header row (title, "Show
  unassessed", "New name") stays above, unchanged.
- `NameSearchPage.tsx`: replace the `<Grid>` block with
  `<CollapsibleSplit leftPercent={58} storageKey="coldp:split:names:collapsed"
  left={<MantineReactTable …/>} right={…TaxonDetail or placeholder…} />`. The
  header, filters, and bulk-action rows above stay unchanged.

`TaxonDetail` and every other component are untouched.

## Testing

`frontend/src/components/CollapsibleSplit.test.tsx`:

- renders both `left` and `right` when expanded
- clicking the collapse control hides `left` (and keeps `right`)
- clicking again restores `left`
- collapsed state is written to `localStorage[storageKey]`
- a pre-seeded `localStorage[storageKey] = true` mounts collapsed

Existing `TreePage.test.tsx` and `NameSearchPage.test.tsx` stay green — both panes
still render by default (expanded).

## Out of scope

- Draggable / resizable divider (a different interaction, not chosen).
- Collapsing the form (right) pane.
- Any change to `TaxonDetail`.
