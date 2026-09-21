# Copy data from the CLB comparison — design

**Date:** 2026-09-21 · **Status:** implemented

## Goal

In the "Compare with ChecklistBank" view, let an editor pull individual values and records from the
CLB target into the focal name with one click (a `«` button on the CLB side), instead of retyping
them or running a whole Import-from-CLB.

## Scope

| Row | Copy action | How |
|---|---|---|
| Name, Authorship, Rank | `«` next to the CLB value when it differs | Fills the value into the open TaxonDetail edit form as an unsaved change; the editor reviews and saves as usual (keeps the normal lock/optimistic-version flow, no stale form) |
| Gender, Etymology, Page (published-in page) | `«` when differing | Into the edit form, as above |
| Published in | `«` when differing | Server creates the CLB citation as a project reference (with CLB provenance); its id is put into the form's published-in field |
| Synonyms | `«` per CLB synonym not already ours, plus "« all" | Whole record copied server-side |
| Vernacular names | `«` per CLB vernacular not already ours (name + language), plus "« all" | Whole record copied server-side |
| Type material | `«` per CLB type record of the focal name not already ours (by citation), plus "« all" | Whole record copied server-side |
| Name relations | — (compare only) | The related name must already exist in the project; copying needs that lookup |
| Status, Accepted name | — | Changing status restructures the tree; not a copy action |
| Classification | — (deferred) | Needs "does this higher taxon already exist?" lookup + wiring into the tree |

Rows that are empty on both sides are hidden.

Copy buttons show only for editors (owner/editor). Record copies (synonyms, vernaculars, type
material) additionally need the focal usage to be ACCEPTED — the same guard as Import-from-CLB's
"update focal" mode; single values can be copied into the form for any status.

## Backend

- `ClbComparison` gains `vernacularNames`, `etymology`, `gender`, `publishedIn` (citation),
  `publishedInPage`, `typeMaterial` (the focal name's) and `nameRelations` (type + related name);
  each `ClbSynonym` gains its CLB usage `id`.
- `POST /api/projects/{pid}/usages/{focalId}/clb-copy` with
  `{datasetKey, taxonId, synonymIds[], vernacularIds[], typeMaterialIds[], publishedIn}` →
  `{summary: ClbImportSummary, publishedInReferenceId}`.
- Implemented on top of `ClbImportService`'s UPDATE_FOCAL path: the fetched CLB bundle is filtered
  down to the chosen synonym / vernacular / type-material ids before insertion, so a copied record is the full CLB
  record (parsed name, authorship, rank, nom. status, published-in / cited references, CLB-scoped
  provenance id), exactly as the importer would bring it.

## Frontend

- `CompareClbModal` takes an optional `onCopyField(field, value)`; TaxonDetail wires it to
  `form.setFieldValue` (and claims the edit lock, like typing would).
- `ClbComparisonView` renders `«` buttons; the modal lists our vernaculars alongside CLB's (with an
  "only here" marker like synonyms) and invalidates the synonymy / vernacular / counts queries after
  a copy.

## Out of scope / later

- Copying classification (higher taxa): look up existing names, create missing ones, re-parent.
- Copying name relations (needs the related name resolved in the project).
- Other child data (distributions, media, …) — the existing Import-from-CLB "update focal" mode
  already covers those in bulk.
