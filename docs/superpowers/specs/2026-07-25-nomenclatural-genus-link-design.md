# Nomenclatural genus link (genus_id) — design

*2026-07-25*

## Problem

A bi/trinomial's gender agreement depends on the grammatical gender of its **nomenclatural genus**
(the genus token in its own name), which for a synonym differs from the accepted classification genus
it hangs under. Today (2026-07-25 fix) this is resolved on the fly by **name-matching** the parsed
`genus` token to a `rank=genus` usage. That works for the common case but is a heuristic: it can't
disambiguate homonymous genera, isn't curatable, and silently guesses.

Pin it explicitly: **link each binomial to its genus usage by id.**

## Model

A nullable **`genus_id`** column on `name_usage`, a self-reference `(project_id, genus_id) →
name_usage(project_id, id)` with `ON DELETE SET NULL` (deleting a genus cleanly unlinks its names).

This is a **project-internal resolution** of the name's own genus token to a usage — distinct from the
basionym link, which is a ColDP `NameRelation` between two independent names. The genus is a *part* of
the name (ColDP export re-derives it from `scientificName`), so `genus_id` is a denormalized
resolution cache (like the `col:` id or the parse cache), **not** exported as a relation.

Applies to bi/trinomials (a parsed `genus` token). Null for uninomials (genus and above — they have
no nomenclatural genus above themselves).

## Gender derivation

`NameUsageService.toResponse` (detail path):
- `genus_id` set → the **linked** genus usage's gender (authoritative), and expose `genusId` +
  `genusName` (the linked genus's scientificName) on the response.
- `genus_id` null → fall back to the existing name-match (`findGenusGenderByName`) as an *unconfirmed*
  gender hint; `genusId`/`genusName` null.

So an unlinked binomial still shows a best-guess gender; a linked one is exact.

## Resolution logic (shared)

`resolveGenusId(projectId, genusToken)`:
- find `rank=genus` usages whose `uninomial` (or `scientific_name`) equals the token;
- exactly one → its id; multiple → the single **accepted** one if there's exactly one, else **null
  (ambiguous)**; none → **null (unmatched)**.

## Establishing the link

### 1. Per-taxon, on the form

A binomial's form shows the **nomenclatural genus**: a searchable Select over the project's
`rank=genus` usages (options from the existing `GET /usages?rank=genus&q=` search), defaulting to the
resolved/linked genus. Picking one (or clearing) writes via a narrow
`PUT /api/projects/{pid}/usages/{id}/genus` `{ genusId, version }` (CAS on the usage version, like
the taxon-info/references narrow writers) → sets/clears `genus_id`. The linked genus's **gender**
shows inline next to the agreement flag.

### 2. Project-wide "Link genera" job

`POST /api/projects/{pid}/link-genera` (owner/editor) — **synchronous** (internal, DB-only, no
external call, so none of the async run/poll machinery the CLB-bound col-match job needs). Iterates
every binomial in the project and, **only for those with a null `genus_id`**, applies `resolveGenusId`
and sets `genus_id` when it resolves to exactly one genus. It **never touches a binomial that already
has a `genus_id`** — an existing link (whether set manually or by an earlier run) is left untouched,
so a curator's choice can never be overwritten. Ambiguous/unmatched stay null for the form to resolve.
Returns `{ linked, ambiguous, unmatched, skipped }` (skipped = already linked). Idempotent. Surfaced
as a **"Link genera"** button in the project **Tools** tab, showing the counts (mirrors the
Export/Match sections). (A separate "relink all, overriding existing" action could be a later toggle,
but the default action is fill-missing-only.)

### 3. Clear-on-stale (update)

`NameUsageService.update`: when the saved name's parsed genus token differs from the loaded row's
(the genus changed), set `genus_id = null` — the old link is stale and must be re-established (by the
form or the job). No silent auto-re-resolve, so a curated link is never quietly changed under the user.

## Scope guardrails (v1 excludes)

Auto-on-import genus linking (deferred); per-taxon **issue flags** for unlinked/ambiguous genera (the
form + the job's counts are the surface); exporting `genus_id` as a ColDP relation (it isn't one);
relinking usages that already carry a manual `genus_id` in the bulk job.

## Backend

- Migration **V8**: add `genus_id` + the self-FK.
- `NameUsage` + `NameUsageMapper`: `genus_id` in select/insert/update; `resolveGenusId`; a narrow
  `updateGenusId` (CAS); a `linkGenera` batch (set `genus_id` where null and resolvable); the detail
  read joins the linked genus for `genusName`/gender.
- `NameUsageService`: gender derivation through `genus_id`; clear-on-stale in `update`; `updateGenusId`
  (owner/editor, narrow, bumps version); a `LinkGeneraService`/method + controller for the batch.
- `NameUsageResponse`: `genusId`, `genusName` (detail only).

### Tests
- `resolveGenusId`: single match, homonym-prefers-accepted, ambiguous→null, none→null (unit/IT).
- `GenusLinkIT`: the batch links resolvable binomials + returns counts, leaves ambiguous null, and
  doesn't overwrite a manual link; the narrow `PUT .../genus` sets/clears + CAS 409; gender derives
  from the linked genus (extending `NameGenderIT`'s synonym case to a *linked* Pinus with a different
  gender than the name-match would pick); `update` clears `genus_id` when the genus token changes.

## Frontend

- `TaxonDetail` (binomial only): a **Nomenclatural genus** Select (searchable over `rank=genus`
  usages) seeded from `genusId`/`genusName`, next to the gender-agreement row; the shown agreement
  gender follows the link. Saving the pick calls `PUT .../genus`; a name edit that changes the genus
  clears it (reflected on the next load).
- Project **Tools** tab: a **"Link genera"** button → `POST /link-genera`, toasts `linked N,
  ambiguous M, unmatched K`.
- `api/usages.ts`: `updateGenusId`, `linkGenera`; `api/projects` or issues wrapper as needed.

### Tests
- Frontend: the genus Select renders on a binomial seeded from the link, changing it PUTs, the shown
  gender follows the linked genus; the Tools "Link genera" button posts and shows counts.

## Rollout

Backend first (migration + resolution + gender wiring + narrow endpoint + batch + ITs), then the form
Select and the Tools button. Commit per green layer on `main`.
