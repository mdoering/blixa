# References editor (search table + BibTeX import + DOI/Crossref)

**Date:** 2026-07-09
**Scope:** A project-level References section over the existing reference CRUD/search, plus DOI
(Crossref) resolution and BibTeX import. Two phases: backend import, then the UI.

## A. Backend

- **Dependency:** add `org.jbibtex:jbibtex` (BibTeX parsing) to `backend/pom.xml`.
- **`CrossrefClient`** (`@Component`, wraps Spring `RestClient`): `JsonNode fetchWork(String doi)` →
  GET `https://api.crossref.org/works/{doi}` with a polite `User-Agent` (mailto), returns the
  `message` node. Maps a 404 → `ResponseStatusException(NOT_FOUND, "DOI not found")`, any other
  failure → `502 (BAD_GATEWAY, "Crossref unavailable")`. Isolated so it can be `@MockitoBean`'d in ITs.
- **`RefMapping`** (static helpers → `CreateReferenceRequest`, the existing create DTO):
  - `fromCrossref(JsonNode message)`: title[0]; authors → `"Family, Given; …"`; container-title[0];
    `issued.date-parts[0][0]` year; volume/issue/page/publisher/DOI/ISBN[0]/ISSN[0]/URL/type;
    a synthesized `citation` (`author (year). title. containerTitle volume(issue): page.`).
  - `fromBibtex(String bibtex)`: `BibTeXParser` → for each entry map title/author (split `" and "`
    → `"; "`)/journal→containerTitle/year→issued/volume/number→issue/pages→page/publisher/doi/isbn/
    issn/url/editor + type; same synthesized citation. Returns `List<CreateReferenceRequest>`.
- **`ReferenceImportService`**: `resolveDoi(uid, pid, doi) → CreateReferenceRequest` (editor;
  fetch + map, **not** persisted — a preview); `importBibtex(uid, pid, bibtex) → List<Reference>`
  (editor; map each → `ReferenceService.create`, in one call).
- **`ReferenceController`** gains: `POST /references/resolve-doi` (body `{doi}`) → the preview
  `CreateReferenceRequest`; `POST /references/import-bibtex` (body `{bibtex}`) → `List<ReferenceResponse>`.
- **Tests:** `RefMappingTest` (unit) — bibtex parse+map (multi-entry), crossref message map from a
  captured sample; `ReferenceImportIT` — POST import-bibtex creates refs (GET /references shows them);
  resolve-doi with a `@MockitoBean CrossrefClient` returning a sample message → 200 + mapped preview.

## B. Frontend (new **References** section)

- **`api/references.ts`**: `listReferences(pid, {q?, limit, offset})`, `getReference`, `createReference`,
  `updateReference`, `deleteReference`, `resolveDoi(pid, doi) → CreateRefPayload`,
  `importBibtex(pid, bibtex) → Reference[]`. Types: `Reference`, `CreateRefPayload`/`UpdateRefPayload`.
- **`ReferencesPage`**: an MRT server-paged search table (debounced `q`; columns author · year ·
  title · containerTitle · doi) with a toolbar — **New reference · Import DOI · Import BibTeX** — and
  per-row edit (opens the form) / delete (owner/editor). Read for any member.
- **`ReferenceForm`** (modal): create/edit all writable fields (citation, type, author, editor, title,
  containerTitle, issued, volume, issue, page, publisher, doi, isbn, issn, link, remarks). Also the
  DOI-preview target: `resolveDoi` → open the form pre-filled → user reviews → Save (normal create).
- **`ImportBibtexModal`**: paste `.bib` text → `importBibtex` → toast "N imported" → refresh table.
- **Nav:** add **References** (`IconBooks`, `/projects/:id/references`) to the sidebar (after Names) + route.

## Verification

Backend `mvn verify`. Frontend `npm test` + build. Browser: on seeded Felidae, open References (the
Linnaeus 1758 seed row shows); resolve a real DOI into the form; paste a small `.bib` and import.

## Out of scope

CSL-JSON import (a later companion to the same mapper); DOI consolidation / find-DOI-for-existing;
per-entry BibTeX preview/selection (import-all for now); reference deduplication.
