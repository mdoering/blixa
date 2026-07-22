# Download name & reference search results as TSV — design

**Date:** 2026-07-22

## Problem

The Names search page and the References page both expose filtered, server-paginated
lists. There's no way to pull the matching set out of the app — a curator who has
filtered to (say) all unassessed genera can't get that list as a file.

## Goal

A "Download TSV" action on each page that downloads **all rows matching the current
filters** (not just the visible page) as a tab-separated file, with columns matching
the on-screen table.

Decisions (from brainstorming, 2026-07-22): **all matching rows**, **columns match the
on-screen table**.

## What exists to build on

- Search endpoints: `NameUsageController` `GET /usages?q=&rank=&status=&limit=&offset=`
  → `NameUsageService.searchPage`; `ReferenceController` `GET /references?q=&yearFrom=&yearTo=&limit=&offset=`
  → `ReferenceService.search`.
- **`life.catalogue.common.io.TabWriter`** (CLB reader lib, already used by `ColdpTsv`)
  writes well-formed TSV, escaping embedded tabs/newlines/CRs.
- Frontend already triggers a binary download via a plain `<a href>` (`exportFileUrl`
  for the ColDP export) rather than the JSON `api()` client.
- Access model to mirror: the ColDP export is open to **any project member** (read-only).

## Design

### Backend — two streaming TSV endpoints

- `GET /api/projects/{pid}/usages.tsv?q=&rank=&status=`
- `GET /api/projects/{pid}/references.tsv?q=&yearFrom=&yearTo=`

Both:

- Require project membership (same `requireRole`/read gate as the search endpoints and
  the ColDP export). Same query params as the paginated search — so the frontend passes
  the *current filters* verbatim.
- Fetch **all matching rows** (no pagination). Implemented by a service method that runs
  the existing search query with the limit/offset removed (a dedicated
  `searchAll`/`exportRows` that reuses the same mapper WHERE clause; a hard safety cap —
  e.g. 100k rows — guards against a pathological project, and if hit is logged, never
  silently truncated).
- Stream the result to the HTTP response through `TabWriter` over
  `response.getOutputStream()`:
  - `Content-Type: text/tab-separated-values; charset=utf-8`
  - `Content-Disposition: attachment; filename="<alias-or-pid>-names.tsv"` /
    `"...-references.tsv"`
- Columns (bare header row, matching the table):
  - **Names:** `id, scientificName, authorship, rank, status`
  - **References:** `id, citation, doi, year`

### Frontend — "Download TSV" button

- `api/usages.ts` / `api/references.ts` get a URL helper mirroring `exportFileUrl`:
  `usageExportTsvUrl(pid, params)` / `referenceExportTsvUrl(pid, params)` that
  serialise the current filters into the query string (same params the search uses,
  minus limit/offset).
- `NameSearchPage` and `ReferencesPage` gain a **"Download TSV"** button
  (`IconDownload`, in the header/filter row) whose `href` is that URL, with the
  `download` attribute. Hidden/disabled when the current result `total` is 0.
- No blob juggling — the browser downloads the attachment directly, same as the ColDP
  export link.

## Testing

- **Backend ITs** (mirroring the existing usage/reference controller ITs):
  - `usages.tsv` returns a TSV whose header is the 5 name columns and whose rows honour
    `q`/`rank`/`status`; a value containing a tab/newline is escaped (round-trips).
  - `references.tsv` returns the 4 reference columns honouring `q`/`yearFrom`/`yearTo`.
  - Non-member is rejected; member allowed.
- **Frontend**:
  - the URL helpers build the expected query string from filters.
  - `NameSearchPage` / `ReferencesPage` render a Download-TSV link with the right `href`
    and hide it when there are no results.

## Out of scope

- Async/queued export (these lists are project-bounded; a synchronous stream is fine —
  the whole-project ColDP export already covers the heavyweight case).
- Choosing columns / formats in the UI, or CSV/Excel variants.
- Downloading the classification tree (this is the two flat search surfaces only).
