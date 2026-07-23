# BHL integration — link names to their exact protologue page — design

**Date:** 2026-07-23
**Status:** approved

## Problem

Much old taxonomic literature has **no DOI** and isn't in Crossref, so a name's
nomenclatural reference can't always be resolved that way. The **Biodiversity Heritage
Library (BHL)** has digitised much of it and indexes pages by the scientific names that
appear on them. ColDP already carries **`publishedInPageLink`** (a URL to the exact page
a name was published — the *protologue* in botany) alongside `publishedInPage` and
`publishedInReferenceId`, and it's already an editable field on the name form. Today the
curator has to find and paste that URL by hand.

## Goal

Tools to **find the exact BHL page** for a name and fill `publishedInPageLink`
(+ `publishedInPage`), without a DOI.

## Confirmed BHL API v3 capabilities (research 2026-07-23)

- **`GetNameMetadata`** — given a scientific name, returns the title/item/page metadata
  for **every page on which the name appears** (BHL's OCR name index). This is the
  "find the page / earliest appearance" lever.
- **`GetItemMetadata`** (pages) — an item id → its pages (page ids, page numbers,
  thumbnails).
- **`GetPageMetadata`** — a page id → page image URL, page number, OCR names.
- **`PublicationSearch`** / **`GetTitleMetadata`** — search titles/items by term
  (title/author/year).
- Base URL `https://www.biodiversitylibrary.org/api3`, `format=json`, requires an
  `apikey` (free).

## Design — the two-level split (per curator steer)

Turn "search all of BHL for one page" into two smaller, reusable steps.

### Piece 1 — reference → BHL item (References editor)

- A **"Find on BHL"** action on a reference searches BHL (`PublicationSearch`, prefilled
  from the reference's title/author/year) → the curator picks the matching **item** (the
  volume) → we store its **BHL item id** on the reference. Done once per reference,
  reused by every name that cites it.
- Storage: a nullable **`bhl_item_id`** column on `reference` (Flyway V3). Set/cleared via
  a dedicated endpoint (`PUT`/`DELETE …/references/{id}/bhl-item`), not threaded through
  the full reference CRUD. The item URL is derived (`…/item/{id}`).

### Piece 2 — name → BHL page (taxon page)

- **Enabled only when** the name's `publishedInReferenceId` points to a reference that has
  a `bhl_item_id`. Then a **"Find page"** action works *within that one item*:
  - Calls `GetNameMetadata` for the focal name and **filters to the linked item** to
    **suggest the exact page(s)** where the name appears (the likely protologue), and/or
    lists the item's pages (`GetItemMetadata`) to browse / jump to the known page number.
  - Picking a page sets **`publishedInPageLink`** = the BHL page URL and
    **`publishedInPage`** = its page number.

## Backend

- **`BhlClient`** (`@Component`, SSRF-safe `RestClient` like `CrossrefClient`): `publicationSearch`,
  `itemPages`, `namePages` (GetNameMetadata). Defensive JSON mapping (guards, like the AI
  adapters) since BHL metadata is uneven.
- Config: **`coldp.bhl.api-key`** (backend only). `GET /api/projects/{pid}/bhl/config →
  {available}` gates the UI (feature hidden when no key), mirroring `/ai/config`.
- Endpoints: `GET …/bhl/publication-search?q=` (Piece 1), `PUT/DELETE …/references/{id}/bhl-item`
  (Piece 1 link), `GET …/bhl/items/{itemId}/name-pages?name=` (Piece 2 suggest within item).
  Read/search: any member; mutations: editor. `bhl_item_id` surfaced on `ReferenceResponse`.

## Frontend

- `api/bhl.ts` (config, search, link/clear, name-pages).
- **Piece 1:** "Find on BHL" on a reference (gated on bhl config) → search modal → pick item
  → stores the link; the reference shows its linked BHL item.
- **Piece 2:** "Find page" on the name form's `publishedInPageLink` field, enabled when the
  name's nomenclatural reference has a BHL item → shows suggested/browsable pages → pick
  sets the link + page.

## Phasing

- **Phase A:** `BhlClient` + config/availability + `PublicationSearch` endpoint + the
  `reference.bhl_item_id` column + set/clear endpoints + References-editor UI.
- **Phase B:** the name→page finder on the taxon page (`GetNameMetadata` within the item)
  + set `publishedInPageLink`/`publishedInPage`.

Each phase is independently shippable and IT-verified (BhlClient `@MockitoBean`'d — it
makes live calls, so it's never test-exercised, same as the AI provider adapters).

## Out of scope (v1)

- Global "earliest appearance" suggestion across all of BHL before a reference is linked
  (noisy given uneven metadata) — a follow-up once the item-scoped flow is proven.
- Writing anything back to BHL; OCR/name-correction; parts (articles) vs items nuance.
