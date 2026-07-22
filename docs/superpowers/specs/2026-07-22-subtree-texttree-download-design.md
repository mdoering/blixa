# Download focal subtree as a TextTree file — design

**Date:** 2026-07-22
**Status:** proposed (sibling of the search-results TSV download)

## Problem / current state

TextTree is **import-only** today (`TxtTreeAdapter` / `TxtTreeToColdp`; the bulk-add
paste box and project import consume it). There is **no TextTree export** and no
download affordance in the tree view. A curator can't pull the subtree under a focal
taxon out as an indented TextTree file.

## Goal

In the tree view, when a taxon is selected, a **"Download subtree"** action that
downloads the accepted subtree rooted at that taxon as a `.txtree` (indented TextTree)
file.

## What exists to build on

- The importer already depends on a TextTree library (GBIF `text-tree` / the CLB
  reader stack) — **confirm it exposes a writer** (`TxtTreeWriter` / equivalent) during
  implementation and reuse it, rather than hand-formatting. TextTree's on-disk shape is
  simple (indentation depth = rank depth; `name authorship [rank]` per line; synonyms
  prefixed `=`/`≡`), so a direct writer is a small fallback if the library has none.
- Tree data: `name_usage` parent links (accepted backbone) + `synonym_accepted` for
  synonyms; `TreeMapper` already walks children.
- Download plumbing: same `<a href download>` pattern as the ColDP export /
  the search-results TSV endpoints.

## Design

### Backend

- `GET /api/projects/{pid}/tree/{id}/subtree.txtree` — any project member (read-only).
- Traverse the **accepted** subtree rooted at `{id}` (depth-first, ordinal order to
  match the tree UI), emitting one indented line per accepted taxon, with each taxon's
  **synonyms nested** beneath it (`=` heterotypic / `≡` homotypic, using the
  `name_relation` basionym data the homotypic feature already maintains). Whether to
  include synonyms is tunable; v1 includes them to match the on-screen synonymy.
- Stream via the TextTree writer to the response: `text/plain; charset=utf-8`,
  `Content-Disposition: attachment; filename="<name>.txtree"`.
- A depth/size safety cap (logged if hit, never silently truncated), like the TSV
  export.

### Frontend

- A **"Download subtree"** button (`IconDownload`) in the tree view — in `TaxonDetail`'s
  header (or the `Breadcrumb` row) when a taxon is selected — whose `href` is
  `/api/projects/{pid}/tree/{id}/subtree.txtree` with the `download` attribute. Hidden
  when nothing is selected.

## Testing

- **Backend IT** — export of a small accepted subtree yields the expected indented
  TextTree (parent, indented children in ordinal order, nested synonyms); non-member
  rejected.
- **Frontend** — the button renders with the right `href` for the selected taxon and is
  absent with no selection.

## Out of scope (v1)

- ColDP/other formats for the subtree (the whole-project ColDP export already exists).
- Choosing depth / whether-to-include-synonyms in the UI (fixed defaults for v1).
- Exporting from the flat Names search (that surface uses the TSV download instead).

## Relationship to the TSV download

Same "export what I'm looking at" theme and the same download plumbing as
`2026-07-22-search-results-tsv-download-design.md`. Implement after the TSV endpoints so
the streaming-download controller pattern is established once and reused.
