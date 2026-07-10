# Taxon form + References tab + project-driven identifiers — design

**Status:** approved (brainstorm) · **Date:** 2026-07-10

## Context & goal

UI/data feedback on the taxon editor:
- The Details form is missing the **published-in reference** and a **notes/remarks** field.
- The general **`link`** field is not wanted (the project is the authoritative source, no primary link to
  point at) — remove it from the UI and the data model.
- Instead, projects want to collect **outbound identifiers** (e.g. IPNI for botanical projects). A
  **project setting** lists the desired identifier **scopes**, which then render as real taxon-form fields
  (stored as `alternative_id` CURIEs).
- Editors want to see/manage all **references and web pages** used by a name usage — a new **References tab**
  over the ColDP taxonomic `referenceID` list (our `reference_id[]`), with an easy way to attach a **web
  URL** without filling in a full citation.

## Data-model changes — migration V16

- `ALTER TABLE name_usage DROP COLUMN link;` (the general name link; **keep `published_in_page_link`** and
  child-entity link fields).
- `ALTER TABLE project ADD COLUMN identifier_scopes TEXT[];` (the scopes to surface as taxon-form fields).
- `ALTER TABLE reference ADD COLUMN accessed TEXT;` (CSL `accessed` / BibTeX `urldate`, ISO date string, for
  webpage references).

## 1. Details form (frontend + small backend)

- **Add `publishedInReferenceId`** — an async reference picker (`EntitySelect` over the project's references,
  loading via the existing reference list; `current` label from the loaded usage). Already on the model +
  update payload.
- **Add `remarks`** — a textarea. Already on the model + update payload.
- **Per-scope identifier fields** — for each scope in `project.identifierScopes`, render one labelled input
  (label = scope upper-cased, e.g. "IPNI"). Value ↔ the `<scope>:<value>` CURIE in `alternative_id`
  (parse on load; on save, merge back, **replacing that scope's entry and preserving all others** incl.
  `col:` from COL matching). To keep a single Save, thread `alternativeId` into the usage update:
  add `alternativeId` to `UpdateNameUsageRequest` + the update path writes `alternative_id`. (The dedicated
  `PUT …/identifiers` from the COL-match work stays; both write the same column.)
- **Remove `link`** from the form, `UpdateUsagePayload`/`NameUsage` (frontend types), `UpdateNameUsageRequest`,
  `CreateNameUsageRequest`, `NameUsageResponse`, `NameUsage` POJO, `NameUsageMapper` (SELECT/INSERT/UPDATE),
  and the export `NameUsageColdpWriter` (drop the `link` column mapping). Grep-clean `name_usage`'s link.

## 2. Project setting — identifier scopes

- `project.identifier_scopes` exposed as `identifierScopes: string[]` on `ProjectResponse` +
  `UpdateProjectMetadataRequest` (mapper insert/update/select; null-safe carry-over like
  `gbifOccurrenceLayer`).
- Project settings page: a Mantine multi-select seeded from `GET /api/coldp/id-scopes` **plus free-text
  custom** scopes (Mantine `TagsInput` or a creatable `MultiSelect`). This drives the Details fields in §1.

## 3. References tab (taxonomic references + web URLs)

- New **References** tab on `TaxonDetail` (any usage). Lists the usage's `reference_id[]` resolved to
  references (show citation or, for webpages, title + a "web" badge; link opens the URL).
- **Add existing** (an `EntitySelect` over project references) and **remove** → new
  `PUT /api/projects/{pid}/usages/{uid}/references` — body `{ referenceIds: int[], version }`, optimistic-
  locked, audited, publishes a `ValidationEvent`; mirrors the identifiers PUT. Sets `name_usage.reference_id`.
- **Add web URL** → `POST /api/projects/{pid}/usages/{uid}/web-reference` — body `{ url }`:
  1. `WebPageClient.fetchTitle(url)` — server-side GET (browsers can't read a cross-origin `<title>` — CORS).
     **SSRF-guarded** (below). Extract the `<title>` (decode HTML entities); fall back to the URL if none.
  2. Create a `Reference` via the existing reference-create path: `type="webpage"`, `title`=the fetched
     title, `link`=the URL, `accessed`=today (ISO), `author`=the host/site as a literal when derivable
     (else null). Allocate our id.
  3. Add the new reference id to the usage's `reference_id[]` (via the same set path as the references PUT).
  4. Return the updated reference list (or the created reference + version).
- ColDP maps `reference_id[]` → the pipe-list `referenceID` on NameUsage (already an array in our model).

### WebPageClient SSRF guards
Mirror `CrossrefClient` (isolated `@Component`, static `RestClient.builder()`), plus: allow only `http`/`https`
schemes; resolve the host and **reject** loopback/link-local/site-local/any-local/multicast addresses (block
internal-network access); a short connect+read **timeout**; cap the response body size (read at most ~512 KB
to find `<title>`); on any failure fall back to a minimal reference (title = the URL) rather than erroring the
whole add. `@MockitoBean`'d in tests — never a real network call.

## Reference model `accessed` ripple
`accessed` added to: `Reference` POJO, `ReferenceMapper` (SELECT/INSERT/UPDATE), `ReferenceResponse`,
`CreateReferenceRequest`/`UpdateReferenceRequest`, the reference edit form (a date/text field), the
CSL/BibTeX import mapping (`RefMapping` — CSL `accessed`, BibTeX `urldate`), and the ColDP export
`ReferenceColdpWriter` (map to `ColdpTerm.accessed` if that column exists; else omit + note).

## Testing
- Backend ITs: the references PUT (set/replace/409); the web-reference POST (mocked `WebPageClient` → asserts a
  `type=webpage` reference created with the fetched title + `accessed` + linked to `reference_id[]`); SSRF guard
  unit test (a `http://127.0.0.1/...` / `http://169.254.x` URL rejected); `alternativeId` round-trips through
  `updateUsage`; `identifier_scopes` + `reference.accessed` default/round-trip; grep-clean `name_usage.link`
  removed (full `mvn verify`, schema migration).
- Frontend: Details form renders publishedIn/remarks + the per-scope fields from a mocked project + round-trips
  a scope value into `alternativeId`; the References tab lists + adds existing + adds a web URL (MSW) + removes;
  Project settings scope multi-select. `vitest` + build.

## Build phases (each independently shippable)
1. **V16 + drop `name_usage.link`** (migration + backend/frontend/export cleanup) — full verify green.
2. **Details form: publishedInReference + remarks** (frontend; `alternativeId` into `UpdateNameUsageRequest`).
3. **Project identifier scopes** (backend setting + Project multi-select) **+ the per-scope Details fields**.
4. **`reference.accessed`** (model + form + import + export).
5. **References tab** — `PUT …/references` + `POST …/web-reference` + `WebPageClient` (SSRF) + the tab UI.

## Caveats
- Fetching a user-supplied URL server-side is the SSRF surface; the guards above are the mitigation. Consider a
  small allow/deny policy or a size/redirect cap tightening later.
- Web-reference is one-shot (no preview step), unlike DOI import — per the "make it easy" ask.
- `author` derivation for a webpage is best-effort (host literal); the user can edit the reference afterwards.
