# Public landing + public project pages — design

**Status:** approved design, ready for planning
**Date:** 2026-07-12

## Goal

Give Blixa a public face:

- A **public landing page** at `/` — a short description of the app, a list of
  **published (public) projects**, and an **environment-aware sign-in** (a local
  username/password form on localhost/dev where ORCID isn't configured, or a
  "Sign in with ORCID" link on a proper domain where it is).
- A **public info page per published project** at its own linkable URL
  (`/p/{id}`): metadata, contributors, metrics, and **release version history
  with downloads**.
- A **lightweight release mechanism**: an owner publishes a versioned,
  persisted ColDP snapshot with a captured metrics snapshot; the list of a
  project's releases is its version history.

Everything public is read-only and unauthenticated; only projects the owner has
explicitly marked public are ever exposed.

## What exists today (and the gaps this fills)

- **Exports** (`coldp/export`): ad-hoc ColDP zips written by `ColdpWriter.write`,
  **auto-deleted hourly** by `ExportRetentionSweep`. Good for "download my
  current data"; not a persistent, versioned release. Releases reuse
  `ColdpWriter` but persist to a separate directory that the sweep never touches.
- **No `public` flag** on `project`; **no `release` table**; the API is entirely
  `authenticated()` except `/api/ping`, `/api/auth/login`, `/login/**`,
  `/oauth2/**`, `/pdf/**`.
- **No public config endpoint** — the SPA can't tell whether ORCID is configured.
- The audit/history table is **`change`** (`project_id, user_id, at, entity_type,
  operation, …`), joined to `app_user` for the editor's history view — the source
  for "changes since last release" and per-user contributions.
- Child entity tables: `vernacular, distribution, media, type_material,
  name_relation, property, estimate, reference` — the source for the child-count
  metrics.
- Routing: `App.tsx` wraps **everything** (including `/` = the project list) in
  `RequireAuth` + `AppLayout`. This must be split into public and authed subtrees.

## Data model

### `project.public`
Add `public BOOLEAN NOT NULL DEFAULT false`. Owner-toggled. Only `public=true`
projects appear in the public API/pages.

### `release` table (new)
An immutable, persisted ColDP snapshot of a project.

| column | type | notes |
|---|---|---|
| `id` | serial PK | |
| `project_id` | int FK → project(id) ON DELETE CASCADE | |
| `version` | text NOT NULL | owner-supplied label, e.g. "1.0", "2026-07" |
| `notes` | text NULL | optional changelog/notes |
| `status` | text NOT NULL | `BUILDING` / `READY` / `FAILED` |
| `name_usage_count` | int NULL | headline metric, kept as a column for the list query (null until READY) |
| `metrics` | jsonb NULL | the rich metrics snapshot (see below) |
| `file_path` | text NULL | server path of the persisted zip (null until READY) |
| `file_name` | text NULL | download filename |
| `file_size` | bigint NULL | |
| `error` | text NULL | failure message when FAILED |
| `created_by` | int → app_user(id) | |
| `created_at` | timestamptz NOT NULL default now() | |

Release files live under a dedicated `coldp.release.dir` (default
`${java.io.tmpdir}/coldp-releases`), **separate from the export dir**, so
`ExportRetentionSweep` never deletes them.

### Metrics snapshot (`release.metrics` JSONB)
Everything except the headline `name_usage_count` (which is its own column for
cheap list/sort/display) lives here, captured at publish time:

```json
{
  "acceptedByRank":  { "genus": 12, "species": 340, "subspecies": 20 },
  "synonymsByRank":  { "species": 118, "subspecies": 6 },
  "supplementary": {
    "vernacular": 50, "distribution": 210, "media": 5, "typeMaterial": 30,
    "nameRelation": 15, "property": 2, "estimate": 1, "reference": 80
  },
  "changesSinceLastRelease": 421,
  "contributions": [
    { "userId": 1, "name": "Markus Döring", "orcid": "0000-...", "count": 300 },
    { "userId": 7, "name": "Jane Doe",      "orcid": null,       "count": 121 }
  ]
}
```

- `acceptedByRank`: `name_usage` grouped by `rank` where `status = 'ACCEPTED'`.
- `synonymsByRank`: grouped by `rank` where `status IN ('SYNONYM','MISAPPLIED')`.
  (Unassessed usages are excluded from both; the totals need not sum to
  `name_usage_count`.)
- `supplementary`: one count per child/supplementary entity table listed above.
- `changesSinceLastRelease`: `count(*) FROM change WHERE project_id = ? AND at >
  {previous READY release's created_at}` (for the first release, since project
  creation, i.e. no lower bound).
- `contributions`: the same `change` window grouped by `user_id`, joined to
  `app_user` for `name` (displayName, falling back to username) and `orcid`,
  ordered by count desc.

A `ReleaseMetricsService` computes all of this with count/group-by queries; it
runs inside the async build job (below), reading the previous release boundary.

## Release lifecycle

Owner-only, authenticated, under the existing project routes:

- `POST /api/projects/{id}/releases` `{version, notes?}` → validates owner,
  inserts a `BUILDING` release row synchronously (so the response has a real id
  to poll), then hands off to an `@Async` job (mirroring `ExportRunService`'s
  single-thread executor + startup stale-sweep recovery). The job:
  1. `ColdpWriter.write(projectId, releaseDir/{releaseId}.zip)` → counts + file,
  2. `ReleaseMetricsService` computes the metrics snapshot,
  3. flips the row to `READY` with file info + `name_usage_count` + `metrics`,
     or to `FAILED` with an error (and deletes any partial file).
- `GET /api/projects/{id}/releases` (any member) → the project's releases,
  newest first, for the editor UI to render + poll a BUILDING one.
- `DELETE /api/projects/{id}/releases/{rid}` (owner) → delete the row + its file
  (post-commit file delete via `TransactionSynchronization`, like PdfController).
- Recovery: a startup sweep fails any `BUILDING` release left behind by a crash
  (same pattern as import/export recovery).

Distinct from the transient **export** (kept as the quick "download current
working data" path in the editor).

## Public read API — unauthenticated

`SecurityConfig`: add `permitAll` for `/api/public/**` and `/api/config`.
Everything else stays `authenticated()`.

- `GET /api/config` → `{ "orcidEnabled": boolean }`. True iff the ORCID OAuth2
  client is configured (derive from the presence of the client-id property /
  the `ClientRegistrationRepository` bean). Drives the env-aware login.
- `GET /api/public/projects` → **public projects only**, each:
  `{ id, title, alias, description, latestVersion, latestReleasedAt,
  nameUsageCount }` (`nameUsageCount`/`latestVersion` from the latest READY
  release, or null if none).
- `GET /api/public/projects/{idOrAlias}` → **404 unless `public=true`**:
  - metadata: `title, alias, description, license, nomCode, geographicScope,
    taxonomicScope`,
  - `contributors`: owner/editor members only (**viewers excluded**), each
    `{ name, orcid, role }` — never emails,
  - `metrics`: the latest READY release's snapshot if any (headline
    `nameUsageCount` + the JSONB fields); if no release, basic live counts
    (`nameUsageCount`, accepted, synonyms, references) with the rich breakdown
    omitted,
  - `releases`: READY releases, newest first — `{ id, version, notes, createdAt,
    fileName, fileSize, nameUsageCount, metrics, downloadUrl }`.
  - Alias: a non-numeric `{idOrAlias}` resolves via `project.alias`; the response
    carries the canonical `id` so the SPA can redirect `/p/{alias}` → `/p/{id}`.
- `GET /api/public/projects/{id}/releases/{rid}/download` → streams the persisted
  release zip; **only for public projects and READY releases**, else 404.

Privacy: only public projects are reachable; only owner/editor identity
(name + ORCID + role) is exposed; no emails, no viewers, no private-project data,
no member list for non-public projects.

## Public frontend

### Routing restructure (`App.tsx`)
Split into public (outside `RequireAuth`) and authed subtrees:

- Public: `/` → `LandingPage`; `/p/:idOrAlias` → `PublicProjectPage`;
  `/login` → existing `LoginPage`.
- Authed (`RequireAuth` + `AppLayout`): **`/projects`** → `ProjectListPage`
  (moved from `/`); `/projects/:projectId/*` → the editor (unchanged).
- Post-login redirect target changes from `/` to `/projects`.
- A slim **public header** (Blixa logo + a right-side "Sign in" when anonymous /
  "My projects" when authenticated) wraps the public pages — distinct from the
  authed `AppLayout` (no sidebar).

### `LandingPage` (`/`)
- Short Blixa description (initial copy: *"Blixa is a lightweight editor for
  building and releasing taxonomic checklists in the Catalogue of Life Data
  Package (ColDP) format."* — final copy owner-editable).
- A grid of public-project cards (title, short description, latest version +
  date, name count) linking to `/p/{id}`.
- A **login area** driven by `GET /api/config` + `GET /api/me`:
  - already signed in → "My projects" (→ `/projects`),
  - else `orcidEnabled` → "Sign in with ORCID" button,
  - else (localhost/dev) → the local username/password form inline (reusing the
    `LoginPage` form logic).

### `PublicProjectPage` (`/p/:idOrAlias`)
Fetches `/api/public/projects/{idOrAlias}` (redirects `/p/{alias}` → `/p/{id}`
using the returned canonical id). Renders: header (title, alias, description);
metadata (license, scopes, nom. code); **Contributors** (name + linked ORCID +
role); **Metrics** (headline counts + by-rank and child breakdowns when present);
**Releases** table (version, date, size, per-release metrics, Download link).
404 → a friendly "not found or not public" page.

### Editor UI additions (`ProjectMetadataPage`)
- A **Public** toggle (owner-only) — flips `project.public`.
- A **Releases** section (owner): "Publish release" (version + optional notes)
  → poll BUILDING → READY/FAILED; a history table with per-release metrics,
  download, and delete. Members see the history read-only.

## Security / privacy summary

- `permitAll`: `/api/public/**`, `/api/config`. Everything else unchanged.
- Non-public projects: every public endpoint returns 404 for them.
- Contributors: owner/editor only; name + ORCID + role; never email/username-as-
  email, never viewers.
- Release downloads: public + READY only.

## Testing strategy

**Backend ITs:**
- publish a release → BUILDING then READY with a persisted file, correct
  `name_usage_count` + metrics snapshot (by-rank, child counts, changes-since,
  contributions); a non-owner gets 403;
- `ExportRetentionSweep` does not touch release files (release dir ≠ export dir);
- `changesSinceLastRelease`/`contributions` reflect only `change` rows after the
  previous release (seed edits across two releases);
- public list shows only public projects; `GET /api/public/projects/{id}` is 404
  for a private project, 200 for a public one; contributors exclude viewers and
  carry no email; alias resolves to the canonical id;
- release download is public for a public project's READY release, 404 for a
  private project or a non-READY release;
- `GET /api/config` reflects ORCID configuration (present vs absent);
- BUILDING-release startup recovery marks a stale row FAILED.

**Frontend:**
- `LandingPage`: renders public-project cards; the login area switches between
  ORCID button / local form / "My projects" per mocked `/api/config` + `/api/me`;
- `PublicProjectPage`: renders metadata, contributors, metrics breakdowns, and a
  releases table with working download links; `/p/{alias}` redirects to `/p/{id}`;
  404 state;
- routing: an anonymous user can reach `/` and `/p/:id`; an authed user is sent
  to `/projects` post-login; the project list lives at `/projects`;
- `ProjectMetadataPage`: the Public toggle and the publish-release/poll/history/
  delete flow.

## Decomposition — one spec, three phases

Each phase is independently shippable:

1. **Releases + public flag + editor UI** — migration (`project.public` +
   `release` table), `ReleaseService` (async build reusing `ColdpWriter`),
   `ReleaseMetricsService`, owner endpoints (publish/list/delete), recovery, and
   the `ProjectMetadataPage` Public toggle + Releases section.
2. **Public read API + `/api/config` + security** — `permitAll` changes, the
   public controllers/DTOs (projects list, project info, release download),
   alias resolution, and the config endpoint.
3. **Public frontend** — the routing restructure, `LandingPage`,
   `PublicProjectPage`, the slim public header, and the env-aware login.

## Out of scope / future

- Full CLB-style immutable/id-stable releases (this is the lightweight version).
- SSR / `<meta>` / schema.org for the public pages (SPA client-render only; the
  CoL portal already does rich SSR for its own routes).
- Per-contributor opt-out, custom author ordering, citation strings.
- Public search/browse of a released dataset's names (the public page links to
  the download; browsing stays in the authed editor / CLB).
- Making Gender/Environment editable (unrelated).
