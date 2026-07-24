# Name gender & gender agreement — design

**Date:** 2026-07-24
**Status:** approved

## Problem

ColDP `Name` has `gender` and `genderAgreement`. `gender` is already fully wired in the backend
(DB column, mapper, DTOs, ColDP export/import, `VocabController` serves `MASCULINE/FEMININE/NEUTER`)
but isn't offered in the name form. `genderAgreement` doesn't exist yet. Both should be editable in
the form, with rank-appropriate behaviour.

## Behaviour (per rank)

- **Genus** (`rank === 'genus'`): **gender** is editable (a Select). The genus stores its own gender.
- **Species and below** (has a `specificEpithet` — a bi/trinomial): the gender is **defined by the
  nearest genus ancestor**, so it's shown **read-only** ("from parent genus"), and a **gender-agreement
  checkbox** appears next to it. The species stores `genderAgreement` (whether its epithets follow the
  genus gender, e.g. *alba*/*albus*); its own `gender` stays null.
- **Suprageneric** (above genus — no specific epithet, not a genus): **neither field is shown**.

## Backend

- **`genderAgreement`** (mirrors the existing `extinct` Boolean end-to-end):
  - Migration **V7**: `ALTER TABLE name_usage ADD COLUMN gender_agreement boolean;`
  - `NameUsage.genderAgreement` (Boolean) + getter/setter.
  - `NameUsageMapper` insert/update (select auto-maps via underscore→camel).
  - `CreateNameUsageRequest` / `UpdateNameUsageRequest` / `NameUsageResponse` gain `genderAgreement`.
  - `NameUsageService` create/update set it.
  - `NameUsageColdpWriter` writes it (`String.valueOf`, like `extinct`); `ImportRunService` reads it
    (`ColdpParse.parseBoolean`, and add `ColdpTerm.genderAgreement` to the writer's/importer's columns).
- **Derived `ancestorGenusGender`** (read-only, detail only):
  - `NameUsageMapper.findAncestorGenusGender(projectId, id)` — recursive CTE returning the nearest
    STRICT genus ancestor's `gender`, mirroring `findAncestorGenusName`.
  - Added to `NameUsageResponse` (a new `of(...)` arg), computed in the detail/build path
    (`get`, synonyms, accepted, create/update responses) — **not** the paginated search (`UsagePage`),
    so no N+1 across list rows.

## Frontend

- `types.ts`: `NameUsage.genderAgreement` (boolean|null) + `ancestorGenusGender` (string|null); the
  create/update payload gains `genderAgreement`.
- `TaxonDetail` form:
  - form values gain `gender` (string) + `genderAgreement` (boolean).
  - **Genus**: an editable **Gender** Select from the vocab (clearable).
  - **Species+** (`usage.specificEpithet` present): a read-only gender from `usage.ancestorGenusGender`
    ("from parent genus", or "—" when the parent genus has none) + a **Gender agreement** Checkbox.
  - **Suprageneric**: hide both.
  - Submit: genus → send `gender`, `genderAgreement: null`; species+ → send `genderAgreement`,
    `gender: null`; else → both null.

## Testing

- Backend: `genderAgreement` round-trips create → get → update (IT); `findAncestorGenusGender` returns
  the parent genus's gender for a species and null for a genus/suprageneric (IT); ColDP export→reimport
  preserves `genderAgreement` (extend the round-trip IT).
- Frontend: the form shows an editable gender Select for a genus, a read-only derived gender + agreement
  checkbox for a species, and neither for a family; submit sends the right fields per rank.

## Out of scope (deferred)

- A **gender-agreement validation rule**. Latin adjective agreement is irregular, so an eager rule
  risks false positives; a later pass could flag only the obvious `-us`/`-a`/`-um` termination cases.
