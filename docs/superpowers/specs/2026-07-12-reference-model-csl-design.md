# Reference model overhaul: structured CSL data + generated citations — design

**Status:** approved direction (owner chose **structured CslName authors** + **full overhaul**). Reuses
CLB's CSL engine. Commits stay local on `main`.
**Date:** 2026-07-12

## Goal

Turn references from loosely-typed rows with free-text authors and a hand-typed citation into
**structured CSL data** whose **citation is generated** (not hand-edited, so it can't drift), rendered
by CLB's bundled CSL engine in a **project-selectable style** (default APA). Concretely, the four
todo-next reference-model items:

- **R1 — Type is a controlled vocabulary.** `reference.type` becomes a validated `CSLType`
  (`life.catalogue.api.model.CSLType` — the CSL-JSON type set: `article-journal`, `book`, `chapter`,
  `dataset`, …). Free strings are rejected on write; the form offers a dropdown.
- **R2 — Structured authors/editors.** `author`/`editor` become arrays of **`CslName`**
  (`{family, given, droppingParticle, nonDroppingParticle, suffix, isInstitution, literal}`) instead of
  a `"; "`-joined string. Existing string values are parsed on migration (best-effort; unparseable →
  `literal`).
- **R3 — Abbreviated forms.** Botany cites abbreviated journal/reference titles. CSL already models
  this: add **`container_title_short`** (CSL `container-title-short`) and reuse `journalAbbreviation`;
  abbreviated *authors* are a CSL rendering concern (a style that emits initials), not a stored field.
- **R4 — Generated citation, no dual-edit drift.** When a reference has structured data, its
  `citation` is **computed** by CLB's `CslUtil`/`CslFormatter` from a `CslData` view of the row, in the
  **project's CSL style** (`project.csl_style`, default `apa`), and shown read-only. A reference that
  only ever had a raw citation string (no structured fields) keeps its manual citation. You never edit
  both the structured fields and the citation string.

## Reuse (already on the classpath — no new deps)

CLB's `org.catalogueoflife:api` (transitively present; source of the `License` enum) ships:
`life.catalogue.common.csl.CslUtil` / `CslFormatter` (with a `STYLE` enum + `FORMAT`),
`CslDataConverter`, `life.catalogue.api.model.{CslData, CslName, CslDate, CSLType, Citation}`, and
bundled `csl-styles/*.csl` (`apa`, `chicago`, `harvard`, `ieee`, `mla`, `cse`, `ejt`, `taxon`) backed
by `de.undercouch:citeproc-java`. We build a `CslData` from a reference and call CLB's formatter — we
do **not** vendor citeproc ourselves. (The exact `CslUtil`/`CslFormatter` entry point — e.g.
`CslUtil.buildCitation(CslData)` vs a `CslFormatter` configured with a `STYLE` — is confirmed against
the actual API in Task 1.)

## Data model

Migration **V23+ (next free number)**, on `reference`:
- `author` / `editor`: **replace** the two `TEXT` columns with `JSONB` arrays of `CslName`. Add
  `author_csl JSONB` / `editor_csl JSONB`, **backfill** from the existing text (split on `"; "`, then
  split each entry on the first `", "` → `family` / `given`; an institution/unsplittable token →
  `{literal: <raw>}`), then drop the old text columns and rename. Preserve `alternative_id`/`version`.
- `container_title_short TEXT` (CSL `container-title-short` / journal abbreviation).
- Keep the existing flat CSL-ish columns (`title, container_title, issued, volume, issue, page,
  publisher, doi, isbn, issn, …`) — they already map cleanly onto `CslData` scalar fields.
- `citation TEXT` stays, but for structured references it holds the **generated** string (recomputed on
  write); it is only user-authored when the reference has no structured content.
- A `citation_manual BOOLEAN NOT NULL DEFAULT false` flag distinguishes "citation was typed by a human,
  leave it alone" (import of a citation-only reference) from "citation is generated, keep it in sync".
  Set `false` whenever structured fields are written; `true` only when a caller supplies a citation with
  no structured data.

On `project`: `csl_style TEXT NOT NULL DEFAULT 'apa'` — the CSL style used to render this project's
citations (one of the bundled styles).

## Backend

- **`CslType` handling (R1):** `ReferenceService.create/update` validate `type` against `CSLType`
  (tolerant parse of the wire value; 400 on unknown, like `Licenses.parse`). Expose the allowed values
  to the frontend (a `GET /api/vocab/csl-type` or fold into the existing `/api/coldp/vocab`).
- **CslName storage (R2):** `Reference.author`/`editor` become `List<CslName>`; a MyBatis JSONB type
  handler (Jackson) serializes them (mirror any existing JSONB handler, e.g. the metrics blob or
  `StringArrayTypeHandler`'s pattern). `CreateReferenceRequest`/`UpdateReferenceRequest`/`ReferenceResponse`
  carry `List<CslName>` (or a slim DTO of the same shape) instead of `String`.
- **Citation generation (R4):** a `ReferenceCitationService.render(Reference, cslStyle)` builds a
  `CslData` from the row (type, author/editor `CslName[]`, issued `CslDate` from the year, title,
  container-title(+short), volume/issue/page, publisher, DOI, …) and calls CLB's `CslUtil`/`CslFormatter`
  with the project's style; returns the plain-text citation. Called on every create/update of a
  structured reference (and on a project CSL-style change → recompute all generated citations). If the
  reference is `citation_manual`, skip generation and keep the stored string. Failures degrade to a
  simple fallback citation (never 500 a save).
- **Import paths** (`RefMapping.fromCrossref/fromBibtex/fromRis/fromDatacite`): now produce structured
  `CslName[]` authors/editors (they already split names) and set `type`; the generated citation replaces
  the hand-built `citation(...)` string where structured data exists (keep the string builder as the
  `citation_manual` fallback for sparse imports).
- **ColDP export/import** (`ReferenceColdpWriter`, the reader mapping): ColDP's `Reference` uses a CSL
  `author` structure — emit/consume the structured names (JSON) rather than the joined string. Confirm
  against `org.catalogueoflife:reader`'s ColdpTerm reference fields; keep a round-trip IT green.
- **Project style (R4):** `updateMetadata` accepts `cslStyle` (validated against the bundled style set);
  changing it enqueues/loops a recompute of the project's generated citations.

## Frontend

- **Type (R1):** the reference form's Type field becomes a `<Select>` of `CSLType` values (searchable,
  vocab-driven — mirror the rank/nomStatus searchable-Select pattern).
- **Structured authors/editors (R2):** replace the single Author/Editor text inputs with a small
  **name-list editor** — a row per author (`family`, `given`, optional particle/suffix, an "institution"
  toggle that switches to a single `literal` field), add/remove/reorder. A compact component reused for
  both Author and Editor. Wire values as `CslName[]`.
- **Abbreviated titles (R3):** add a "Container title (short)" field next to Container title.
- **Generated citation (R4):** the Citation field is **read-only** and shows the generated preview when
  the reference has structured data (with a note "generated from the fields above in the project's
  citation style"); editable only for a citation-only reference (the `citation_manual` case, e.g. a bare
  pasted citation). A live preview updates as fields change (a debounced `POST /references/preview-citation`
  or client feedback that it regenerates on save).
- **Project CSL style (R4):** the project **Settings** section gets a "Citation style" `<Select>`
  (APA default, plus the bundled styles) → `updateMetadata({cslStyle})`.

## Testing

- **CslName round-trip:** create a reference with structured authors → stored + returned as `CslName[]`;
  the JSONB handler survives read-back.
- **Citation generation:** a reference with authors/title/journal/year → the stored `citation` equals
  CLB's APA rendering; switching `project.csl_style` regenerates it.
- **No drift:** editing a structured field regenerates the citation; the citation field is not
  independently writable when structured data is present; a `citation_manual` reference keeps its string.
- **Type validation:** unknown type → 400; a valid `CSLType` persists.
- **Migration backfill:** an existing `"Bánki, O.; Döring, M."` author string migrates to two `CslName`s
  (`{family:"Bánki", given:"O."}`, `{family:"Döring", given:"M."}`); an institution-like token → `literal`.
- **Import:** Crossref/BibTeX/RIS/DataCite imports yield structured authors + a generated citation.
- **ColDP round-trip:** export→import preserves structured authors and type.
- **Frontend:** the name-list editor adds/removes authors; Type is a controlled Select; the citation is
  read-only with a preview when structured, editable when citation-only; the project style picker works.

## Decomposition (phases)

1. **Backend model + citation engine:** migration (JSONB authors/editors, `container_title_short`,
   `citation_manual`, `project.csl_style`) + `CslName` on the model/DTOs + JSONB handler + `CslType`
   validation + `ReferenceCitationService` (CLB `CslUtil`) + regenerate-on-write. ITs.
2. **Import + export alignment:** `RefMapping.*` produce structured names + generated citations;
   ColDP writer/reader handle structured authors; round-trip IT.
3. **Frontend:** CSLType Select, the CslName list editor, container-title-short, read-only generated
   citation + preview, project citation-style picker.

## Out of scope / deferred

- Per-reference style override (project-level only).
- A full CSL style *editor*; only the bundled styles are selectable.
- Re-parsing already-migrated `literal` authors into structured names (one-time best-effort at migration
  is enough; users can fix individual names in the new editor).
- Locale/language-specific citation rendering beyond the chosen style's default.
