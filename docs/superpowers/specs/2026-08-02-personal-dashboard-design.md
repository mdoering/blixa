# Personal dashboard (post-login home)

**Date:** 2026-08-02
**Status:** approved in brainstorm (Markus, 2026-08-02)

## Problem

After login a user lands on a project's Metadata page — arbitrary and not personal. Replace the
post-login home with a **personal dashboard**: an "inbox" of things needing my attention across all
my projects, quick access to those projects with a few headline metrics, and (separately) richer
live metrics folded into each project's Releases tab.

## Decisions (from the brainstorm)

- **Framing:** inbox + quick access + basic per-project metrics.
- **Scope:** aggregate across **all** projects I'm a member of (not a single "current" project).
- **Pings:** a **last-visit marker** (`app_user.dashboard_seen_at`), not a full notification store.
- **Detailed metrics:** **folded into the existing Releases tab** (a live "now" block above the
  release history), not a separate tab.
- **Deferred:** a persistent header notification bell with per-item read state — out of v1.
- **Recently edited taxa (by me)** — in v1 (a "resume where I left off" card, from the changelog).

## Routing

- New authenticated route **`/dashboard`** → `DashboardPage` (under `AppLayout`, inside
  `RequireAuth`).
- Post-login redirect target becomes `/dashboard`; the header brand/logo links there.
- An authenticated user hitting `/` (public `LandingPage`) is redirected to `/dashboard`; anonymous
  users still see `LandingPage`.
- `/projects/:id` keeps redirecting to its Metadata tab — it's just no longer the login landing.

## Dashboard content (all cards self-hide when their count is 0)

### Needs attention (inbox)
| Card | Audience | Source | Action |
|---|---|---|---|
| People awaiting approval | global admin | `app_user` rows in state PENDING | → `/admin/users` |
| New pings since last visit | everyone | discussion comments mentioning me OR on threads I follow/authored, `created_at > dashboard_seen_at` | → a pings list (links to each discussion); visiting stamps seen |
| Review submissions to triage | owner/editor | discussions in `REVIEW` in my projects | → that project's discussions (REVIEW filter) |
| Projects missing metadata | owner | my owned projects lacking license / description / geographic or taxonomic scope | → each project's Metadata tab |
| Open errors across my projects | owner/editor | ERROR-severity issue counts per my project | → that project's Issues |
| Taxa I still have locked | anyone holding locks | my active `lock` rows across projects | → resume/release |

### My projects (quick access + basic metrics)
Cards for every project I belong to: title, role badge, **headline counts (accepted · synonyms ·
open issues)**, and quick links (tree / names / issues / releases).

### Recently edited (by me)
A "resume where I left off" card: the most recently edited taxa authored by me across my projects,
deduped by usage (newest edit wins), newest-first, capped (~8). Each row links straight to that
taxon's editor (`/projects/{pid}/names?usage={id}`) with its project + relative edit time.

## Backend

### `GET /api/me/dashboard`
One aggregation call returning everything the dashboard needs, each section computed only when
relevant to the caller:
```jsonc
{
  "pendingUsers": 3,              // null unless global admin
  "pings": { "count": 5, "items": [ { "projectId, discussionId, title, snippet, createdAt" } ] },
  "reviewSubmissions": [ { "projectId, projectTitle, count" } ],
  "missingMetadata": [ { "projectId, projectTitle, missing": ["license","description"] } ],
  "openErrors": [ { "projectId, projectTitle, count" } ],
  "myLocks": [ { "projectId, projectTitle, usageId, scientificName, acquiredAt" } ],
  "recentTaxa": [ { "projectId, projectTitle, usageId, scientificName, editedAt" } ],
  "projects": [ { "id, title, alias, role, accepted, synonyms, openIssues" } ]
}
```
- Cross-project queries are scoped to the caller's `project_member` rows (+ the global-admin
  pending-users query). Headline counts are live indexed `COUNT`s.
- Pings query joins `discussion_comment` → its discussion, filtered to (mentions me — via the stored
  `@username`/`@orcid`/mention rows) OR (I follow / authored the thread), `created_at >
  dashboard_seen_at`, across my projects; capped + newest-first for the preview list.
- `recentTaxa` query: the `change` changelog rows where `actor = me` and `entity_type = 'name_usage'`
  across my projects, taking each usage's latest edit (`GROUP BY` usage, `MAX(created_at)`),
  newest-first, capped (~8); joined to `name_usage` for the current scientific name (skip rows whose
  usage was since deleted).

### `POST /api/me/dashboard/seen`
Stamps `app_user.dashboard_seen_at = now()`. The frontend calls it when the dashboard's pings are
shown, so the count resets on next load. (A comment I authored never counts as my own ping.)

### Migration
`V<n>__dashboard_seen.sql`: `ALTER TABLE app_user ADD COLUMN dashboard_seen_at timestamptz;`
(null = never visited → first visit shows recent pings, then stamps).

### `GET /api/projects/{pid}/metrics` (for the Releases tab block)
Live "now" metrics, reusing/adapting `ReleaseMetricsService`:
- counts by **status** (accepted / synonym / misapplied / unassessed) and by **rank**,
- #references, #type-material records,
- open issues by **severity**,
- **changes since the last READY release** (added / changed / deleted), from the existing
  release-metrics boundary logic.
Owner/editor/viewer may read (same as the rest of the project).

## Frontend

- `DashboardPage` (`src/dashboard/`): one `useQuery(['dashboard'], getDashboard)`; renders the inbox
  cards (each gated on its section being present/non-empty) and the project cards. On mount, after
  data with pings loads, fire the `seen` mutation.
- `src/api/dashboard.ts` wrapping the two `/api/me/dashboard*` calls; `src/api/metrics.ts` for the
  project metrics.
- Releases tab (in `ProjectMetadataPage`): a `ProjectMetrics` block at the top of the panel, its own
  `useQuery(['metrics', pid])`.
- `AppLayout` brand link → `/dashboard`; post-login redirect updated.

## Testing

- **Backend (IT):** `MeDashboardIT` — aggregation respects membership (non-member projects absent),
  admin-only `pendingUsers`, pings honor the seen marker + mention/follow/author rules and exclude
  my own comments, `recentTaxa` shows my latest name_usage edits (deduped, deleted usages skipped),
  `seen` stamps and resets the count. `ProjectMetricsIT` — status/rank counts and since-last-release
  deltas.
- **Frontend (vitest + MSW):** `DashboardPage` — cards render/hide by count and role, links target
  the right routes, the seen mutation fires; `ProjectMetrics` renders the counts block.

## Build order

1. Migration + `GET/POST /api/me/dashboard[/seen]` (start with the cheap sections: pendingUsers,
   projects+headline counts, missingMetadata, openErrors, myLocks, recentTaxa) → `DashboardPage` +
   routing.
2. Add the pings section (mention/follow/author query + seen marker) once the shell is in place.
3. `GET /api/projects/{pid}/metrics` + the Releases-tab metrics block.
