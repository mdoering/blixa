# Compare focal taxon with ChecklistBank — design

**Date:** 2026-07-20
**Status:** approved

From a focal taxon in the editor, compare it side-by-side against a taxon in ChecklistBank
(CLB). Compares scientific name, authorship, rank, status, higher classification, and
synonyms, with differences highlighted. Reuses the existing `clb` integration.

## Flow
Open **"Compare with CLB…"** from a taxon's detail. Choose the CLB target two ways:
- **(A) Across all datasets** — search the focal name globally, pick a hit (shows its dataset).
- **(B) Pick a dataset** — a favorite chip or a dataset search → the focal name is searched
  within that dataset → pick a hit.

Then a **side-by-side** shows *Ours* vs *CLB*: name, authorship, rank, status, classification,
synonyms — differences highlighted.

## Reuse (existing `clb` integration)
- `ClbImportClient.searchDatasets(q)` + `GET /api/clb/datasets` — dataset search.
- `ClbImportClient.searchUsages(datasetKey, q, rank)` + `GET /api/clb/{key}/usages` — in-dataset search.
- `ClbImportClient.usageInfo(datasetKey, id)` → full `UsageInfo` (usage/name/synonyms/classification) — the CLB side.
- `project.identifierScopes` JSONB + `IdentifierScopeListTypeHandler` — the pattern for favorites.
- `MatchColModal` / TaxonDetail modal — the UI entry-point pattern.

## Backend (package `org.catalogueoflife.editor.clb`)
- **`ClbComparison`** DTO: `{datasetKey, datasetTitle, taxonId, link, scientificName, authorship,
  rank, status, classification:[{rank,name}], synonyms:[{scientificName,authorship,status}]}`,
  built from `ClbImportClient.usageInfo(datasetKey, id)` (classification from `UsageInfo.classification`,
  synonyms from `UsageInfo.synonyms`). `link` = the CLB portal URL (`ClbTaxonUrl` helper).
- **`GET /api/clb/{datasetKey}/compare/{taxonId}`** → `ClbComparison`.
- **`ClbImportClient.searchUsagesAllDatasets(q, rank)`** via CLB global `/nameusage/search?q=&limit=`
  (each hit carries `datasetKey`); **`GET /api/clb/usages?q=&rank=`** → `List<ClbGlobalUsageHit
  {datasetKey, datasetTitle, id, scientificName, authorship, rank, status}>`.
- **Favorites:** `project.favorite_clb_datasets` JSONB (V32) = `[{key,title}]`; `FavoriteClbDataset`
  record + `FavoriteClbDatasetListTypeHandler` (mirror `IdentifierScopeListTypeHandler`); read in
  `ProjectResponse` + written by `updateMetadata` (owner/editor; null-safe carry-over like
  `identifierScopes`).

## Frontend
- **`CompareClbModal`** (opened from TaxonDetail via a "Compare with CLB…" action):
  - Target picker with two modes (SegmentedControl): "All datasets" (search box prefilled with the
    focal name → global hits) and "By dataset" (favorite chips + dataset search → in-dataset hits).
  - On pick → fetch `compare/{taxonId}` and render **`ClbComparisonView`**.
- **`ClbComparisonView`**: two columns (*Ours* | *CLB*) with rows: name, authorship, rank, status,
  classification (rank-by-rank), synonyms. Differing values highlighted. The *Ours* column is built
  from the focal `NameUsage` + its existing classification (ancestors) + synonyms endpoints.
- **Favorite datasets** managed in `ProjectMetadataPage` settings: search datasets, add/remove
  favorites (stored via the metadata update). Favorites appear as quick chips in the modal.

## Diff logic
Normalize (trim, collapse whitespace, case-fold where appropriate) then compare name / authorship /
rank / status. Classification compared rank-by-rank on the shared higher ranks. Synonyms diffed by
normalized scientific name; synonyms present on only one side are flagged.

## Testing
- Backend: unit-test `UsageInfo → ClbComparison` mapping and the global-search JSON parsing from
  fixtures. The live-CLB endpoints are not exercised offline (mirrors the existing `ClbImport` tests).
- Frontend: `CompareClbModal` (both picker modes → comparison render), `ClbComparisonView` diff
  highlighting, and favorites management.

## Out of scope (noted)
Distributions / vernacular comparison (free-text, hard to diff); writing CLB values back into the
editor (this is read-only comparison; edits stay manual for now).
