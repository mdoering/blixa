# Shared taxon property keys — design

**Date:** 2026-07-23
**Status:** approved (mirrors the existing journal-title reconciliation)

## Problem

Taxon **properties** (`col:property`) are free-form key/value pairs — the `property`
column is the key, `value` the value. Nothing standardises the keys across a project, so
the same concept ends up spelled several ways (`chromosomeCount` / `chromosome count` /
`Chromosome Number`), keys have no descriptions, and there's no project-wide view of what
keys exist. This mirrors the journal-title problem already solved for references.

## Goal

Manage a project's property keys: **autocomplete** the key when adding a property, an
optional **description** per key, a project-wide **overview** of keys with usage counts,
and **reconciliation** (merge two keys into one).

## What exists to mirror

- References already do this for journal names: `ReferenceMapper.containerTitleFacet`
  (distinct value + count), `ReferenceService.mergeContainerTitle(canonical, variants)`
  (`UPDATE … SET container_title = canonical WHERE container_title IN (variants)`),
  endpoints `GET/POST /references/facets/container-title[/merge]`, and the frontend
  `ReconcileJournalsModal`. Property keys reuse this shape.
- `property` table: `(project_id, id, usage_id, property, value, page, reference_id,
  remarks, ordinal)`. The child editor is `PropertyTab` (`taxonTabs.tsx`), a
  `ChildEntityTab` whose `property` field is a plain text input today.

## Design

### Data — a `property_key` table (Flyway V4)

Descriptions need a home (a distinct-used-keys facet can't hold them). Add a small
project-scoped table of **defined/standard keys**:

```sql
property_key(project_id int, key text, description text, PRIMARY KEY (project_id, key))
```

Used keys (from `property.property`) and defined keys (this table) are unioned in the
overview; a key can be used-but-undefined (no description) or defined-but-unused (count 0).

### Backend

- **Facet:** `PropertyMapper.keyFacet(projectId)` → distinct `property` + count. A
  combined `GET /api/projects/{pid}/property-keys` returns each key with `{key, count,
  description}` (LEFT JOIN the facet with `property_key`; keys defined-but-unused
  included).
- **Define/describe:** `PUT /api/projects/{pid}/property-keys/{key}` body `{description}`
  upserts a `property_key` row (defines a standard key / edits its description). `DELETE
  …/property-keys/{key}` removes the *definition* only (never touches `property` rows).
  Editor-only.
- **Reconcile:** `POST /api/projects/{pid}/property-keys/merge` body `{canonical,
  variants[]}` → `UPDATE property SET property = canonical WHERE project_id = ? AND
  property IN (variants)`; also fold the variants' `property_key` rows into the canonical
  (keep the canonical's description, or the first non-blank). Editor-only. Mirrors
  `mergeContainerTitle`.
- Reads: any project member. Autocomplete reuses `GET /property-keys` (keys only).

### Frontend

- **Autocomplete:** `PropertyTab`'s `property` field becomes a Mantine `Autocomplete`
  sourced from `GET /property-keys` (defined ∪ used keys) — still free-text, so new keys
  can be typed. (Add an `autocomplete` `FieldDef` type to `ChildEntityTab`, or a small
  custom field in `PropertyTab`.)
- **Manager modal** (mirrors `ReconcileJournalsModal`) — `PropertyKeysModal`: a table of
  every key with **count** + editable **description**; **add a key** (define with a
  description); **merge** — tick variants, choose the canonical, confirm → rename. Reached
  from **Project settings** (`ProjectMetadataPage` → Settings), project-wide, next to the
  favorite-CLB-datasets / token controls.

## Testing

- Backend IT: `GET /property-keys` returns used+defined keys with counts + descriptions;
  `PUT`/`DELETE` a definition; `merge` renames `property.property` and folds definitions;
  editor gate; the V4 migration.
- Frontend: the manager modal (list, add/describe, merge) + the autocomplete field
  offering existing keys.

## Out of scope (v1)

- Key value-type validation / controlled vocab per key (just a name + description now).
- Cross-project shared key libraries.
