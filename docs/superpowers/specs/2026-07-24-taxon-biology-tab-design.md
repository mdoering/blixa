# Taxon "Biology" tab — extinct / environment / temporal range + flexible properties

*2026-07-24*

## Problem

Three taxon-level fields round-trip through the model, DTOs, and ColDP but have **no UI**:

- `extinct` (boolean)
- `environment` (marine / freshwater / terrestrial / brackish — multi-value)
- `temporalRangeStart` / `temporalRangeEnd` (geochronological range, GeoTime names)

They live in `taxon_info`, are written only via the full usage create/update, and the TaxonDetail
form merely *seeds* them into its initial values and passes them straight back through from the
loaded usage on save (so saving preserves but never edits them). They are invisible passenger data.

This surfaced while designing DwC-A import: the Species Profile extension maps precisely onto
`extinct` + `environment`, so importing it would land values a curator can't see or change.

## Decisions (from brainstorming)

- Keep the **Details tab nomenclature-only**. Put the biology fields on a **separate tab**.
- **Join them with the flexible taxon Properties** into one tab named **"Biology"** — both are
  taxon-level supplementary data, distinct from the name.
- Temporal range gets a proper **GeoTime** picker (CLB's `GeoTime.TIMES` is available), not free text.

## Scope

Repurpose the existing `Properties` tab (accepted-gated) into a **Biology** tab with two stacked
sections:

1. **Taxon biology** — a small form: Extinct (Checkbox), Environment (MultiSelect), Temporal range
   Start/End (searchable GeoTime Selects), with its own Save.
2. **Properties** — the existing flexible key/value list, unchanged.

Gating stays identical to today's Properties tab (`isAccepted`); broadening any taxon-level tab to
UNASSESSED is a separate, cross-cutting change and out of scope here.

Not in scope: editing biology from the Details/name form; a GeoTime range *validator*
(start-older-than-end); temporal range as numeric Ma.

## Backend

### GeoTime vocab

`VocabController.VocabResponse` gains `geoTimes: List<String>` — every `GeoTime.TIMES` value's
`getName()`, ordered oldest→youngest (by `getStart()` descending; the ICS convention where larger Ma
= older). No persistence change. A `VocabControllerTest`/IT asserts the list is non-empty and
contains a known unit ("Holocene").

### Narrow taxon-info write path

The biology form must save without touching name fields, so add a narrow endpoint mirroring the
existing `PUT /usages/{id}/references` (`updateReferenceIds`) and `/identifiers`
(`updateAlternativeId`) idiom:

```
PUT /api/projects/{pid}/usages/{id}/taxon-info
body: { extinct, environment[], temporalRangeStart, temporalRangeEnd, version }
```

`NameUsageService.updateTaxonInfo`:
- owner/editor gate (`requireEditor`), usage-in-project check;
- CAS-bump the usage `version` (a narrow, version-guarded `name_usage` touch — same 0-rows → 409 as
  the other narrow writers); reuse/extend `writeTaxonInfo` (`TaxonInfoMapper.upsert`, or `delete`
  when all four are empty, matching how create/update already normalize an all-empty taxon_info);
- publish `ValidationEvent.forUsage` and record a changelog entry, like the other writers.

Sharing the usage `version` with the Details form is the same safe optimistic-concurrency pattern
the References tab already uses: editing one tab then the other refetches the shared
`['usage', pid, usageId]` query, so the second save sees the bumped version.

`environment` is parsed with the existing `parseEnvironments` helper (tolerant String → Environment
enum). A `TaxonInfoApiIT` covers: round-trip of all four fields; clearing them (all-empty → row
deleted); a stale `version` → 409; and that the write does **not** alter name fields.

## Frontend

- `api/coldp.ts` `Vocab` type + `getVocab` gain `geoTimes: string[]`.
- `api/usages.ts` gains `updateTaxonInfo(pid, id, payload)` → the narrow PUT.
- `child/taxonTabs.tsx` (or a new `BiologyTab`): the Biology tab renders the biology form above the
  existing Property list. The biology form is its own `useForm` seeded from the usage
  (extinct/environment/temporal), Save calls `updateTaxonInfo`, invalidates `['usage', pid, usageId]`
  and `['usageIssues', ...]`, and toasts. Environment options come from `vocab.environment`
  (title-cased labels, enum-name values); temporal Selects from `vocab.geoTimes`, `searchable`,
  clearable.
- `TaxonDetail.tsx`: rename the `properties` `Tabs.Tab` label to **Biology**; the panel renders the
  new Biology tab. The Details form is left **unchanged** — it keeps carrying
  `extinct`/`environment`/`temporalRange` through from the loaded usage on save (lines ~327–330), so
  the biology PUT is the sole editor and the two write paths never fight (CAS on the shared usage
  version makes a stale Details save 409 rather than clobber a just-saved biology edit).

### Tests

- Biology tab: fields render for an accepted taxon, seeded from the usage; editing + Save calls the
  narrow PUT with the new values; the Property list still renders below. Tab hidden for a synonym.
- The Details form still saves name edits without a biology widget present (regression: nomenclature
  tab unaffected).

## Rollout

Backend first (vocab + narrow endpoint + ITs), then the frontend tab. Commit per green layer on
`main`. This unblocks the DwC-A Species Profile mapping (imported extinct/environment become
visible/editable immediately).
