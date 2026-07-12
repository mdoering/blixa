# Soft-lock activation + "current work" awareness — design

**Status:** autonomous draft (owner offline; owner authorized continuing the queue autonomously). The
one product-shaping decision — *when* a lock is acquired — is called out in **§Decisions** for the
owner to confirm/redirect on review. All commits stay local on `main` (not pushed).
**Date:** 2026-07-12

## Goal

Make the **already-built but frontend-dormant** soft-lock infrastructure visible and useful, so
concurrent editors can see **who is working where** in a project. Today the backend has a complete,
tested `lock` API (`/api/projects/{pid}/locks`, acquire/refresh/release/list, advisory-only,
TTL-based `expires_at`) but **nothing in the UI ever calls it** — no lock indicator, no `api/locks.ts`,
no sweep of expired rows. This feature activates it: auto-acquire a lock while editing a name, surface
active locks in the **tree** and **Names** table, warn (non-blocking) when opening a name someone else
holds, and add a per-project **"Current work"** list of who is editing what right now.

Soft locks stay **advisory** — the optimistic `version` CAS on each entity remains the only *enforced*
write-safety mechanism (design spec §5.9). A lock never blocks another user's write; it is a
"someone is working here" signal.

## Background (ground truth — what already exists)

- **`lock` table** (`V5__lock.sql`, `V6__task.sql`): `id, project_id, entity_type, entity_id, user_id,
  acquired_at, expires_at, task_id?`, `UNIQUE(project_id, entity_type, entity_id)` — one lock per
  entity, taken over in place. `entity_type` is free text; in practice only `"name_usage"`.
- **`LockService`**: `DEFAULT_TTL_SECONDS=300` (min 30, max 3600, **hardcoded**); `acquire`
  (`upsertTakeover` — succeeds only if the row is already mine or expired), `refresh`, `release`,
  `listActive`. Any project **member** may lock (not gated to owner/editor).
- **`LockController`** `/api/projects/{pid}/locks`: `POST` acquire (**200 if `heldByMe`, else 409** with
  the real holder in the body), `GET` list active, `POST /{id}/refresh?ttlSeconds=`, `DELETE /{id}`.
- **`LockResponse`**: `id, entityType, entityId, userId, username, acquiredAt, expiresAt, heldByMe,
  taskId, taskTitle` — the holder's `username` is already denormalized server-side.
- **Gaps** (targets here): no expired-lock **sweep** job (rows linger, lazily ignored); TTL not
  `@Value`-configurable; a `lock` row for a **deleted/merged** `name_usage` is left orphaned (no FK,
  no cleanup — also a deferred minor from the merge-feature review); **no frontend at all.**
- **Tasks** exist too (`task` table + `/tasks` API; a lock/change may carry a `taskId`) but are
  likewise frontend-dormant except a read-only History filter. **Out of scope here** (see §Out of scope).

## Decisions (owner: confirm or redirect)

1. **When is a lock acquired? → auto-acquire on *edit intent*, not on mere viewing, not by an explicit
   "claim" button.** When an **editor** begins editing a name in `TaxonDetail` (the detail form becomes
   dirty, or they invoke an edit action such as a status change), the client auto-acquires the lock,
   **refreshes it on a heartbeat** while the editor stays open, and **auto-releases** on close / switch
   to another name / unmount / save-and-close. Rationale: matches the "someone is working here"
   advisory intent; lowest friction; auto-release avoids the stale-lock problem that plagues explicit
   claim/release. *Rejected alternatives:* (a) explicit "Claim/Release" menu action — friction, users
   forget to release → stale locks; (b) lock-on-open (even to view) — false "editing" signals when a
   user is only browsing. A viewer (read-only role) never acquires a lock.
2. **Where does "current work" live? → a new per-project sidebar route `Activity`** (page title
   "Current work"), listing every **active** lock in the project (name, holder, since, expires) with
   click-through to the name, refreshed live. Rationale: there is no dashboard/home to fold it into,
   and the sidebar-section pattern is a one-line add. It doubles as the collaboration-awareness view.
3. **How do rows learn their lock state? → a single `GET /locks` per project, cross-referenced
   client-side by `entityId`.** No change to the tree/search SQL (the tree mapper deliberately avoids
   joins; adding a `lock` join was explicitly deferred). One cached, periodically-refetched query feeds
   the tree badges, the Names badges, the detail banner, and the Current-work page.
4. **Locks are released when their entity disappears.** Deleting or merging a `name_usage` now also
   deletes its `lock` row(s) (closes the merge-review deferred minor). Cheap, keeps the signal honest.

## Backend changes

Small, additive; no schema migration (the `lock` table already has everything).

1. **`LockRetentionSweep`** — a `@Scheduled` component (mirror `ExportRetentionSweep`; `@EnableScheduling`
   is already app-wide) that periodically `DELETE FROM lock WHERE expires_at <= now()`. Add
   `LockMapper.deleteExpired()` for it. Interval via `@Value("${coldp.lock.sweep:PT5M}")`.
2. **Configurable TTL** — replace the hardcoded `DEFAULT_TTL_SECONDS` with
   `@Value("${coldp.lock.ttl-seconds:300}")` (keep the 30/3600 clamp). Purely so deploy can tune it.
3. **Release-on-disappear** — add `LockMapper.deleteByEntity(projectId, entityType, entityId)` and call
   it from `NameUsageService.delete` and from `MergeRecordsService.mergeUsages` (per merged duplicate
   `d`: `locks.deleteByEntity(projectId, "name_usage", d)`, alongside the existing
   `issues.deleteByEntity`). No lock is *checked* before delete/merge — advisory, unchanged.

Everything else (acquire/refresh/release/list endpoints, `upsertTakeover`, the 200-vs-409 semantics)
is reused as-is. `entity_type` stays `"name_usage"` for this phase.

## Frontend changes

1. **`api/locks.ts`** — typed client over the existing endpoints: `listLocks(pid)` → `Lock[]`,
   `acquireLock(pid, {entityType, entityId, ttlSeconds?})` → `{lock, conflict}` (treat the 409 as a
   *soft* result carrying the real holder, not an error — the client may still edit), `refreshLock(pid,
   lockId, ttlSeconds?)`, `releaseLock(pid, lockId)`. `Lock` type mirrors `LockResponse`.
2. **`useUsageLock(pid, usageId, enabled)` hook** — encapsulates the lifecycle: exposes `claim()`
   (idempotent acquire), starts a heartbeat `refresh` (interval < TTL, e.g. every 2 min for a 5-min
   TTL) once claimed, and `release`s on unmount / when `usageId` changes / when `enabled` goes false.
   `enabled = canEdit`. Returns `{ mine, holder, claim }` where `holder` is the current lock's
   `LockResponse` if held by someone else.
3. **`TaxonDetail` integration** — for editors, call `claim()` on the first edit interaction (form
   dirty / edit action). If the name is **locked by someone else**, show a non-blocking banner:
   *"🔒 {holder.username} is editing this name — your changes may conflict."* (still fully editable; the
   `version` CAS is the real guard). Never claim for viewers.
4. **Lock indicator on rows** — from the shared `listLocks` query (keyed `['locks', pid]`, refetched on
   an interval, e.g. 20 s):
   - **`TreeNodeRow`**: a small lock icon next to the rank badge for any node with an active lock —
     tooltip *"{username} is editing"*; muted/subtle when `heldByMe`, prominent (e.g. orange) when held
     by another. (Locks arrive as a prop derived from the query, so the tree SQL is untouched.)
   - **`NameSearchPage`** table: the same indicator in the name cell.
5. **"Current work" page + route** — new sidebar item **Activity** → `/projects/:pid/activity`,
   rendering `CurrentWorkPage`: a table of the project's active locks (name/entity, holder, "since",
   "expires in") sorted by most-recent, click-through selecting the name (reuse the Names deep-link
   `?usage=` pattern). Rows I hold get a **Release** button; auto-refreshes off `['locks', pid]`.
   Empty state: *"No one is editing anything right now."*

## Testing

- **Backend:** `LockRetentionSweep` deletes only `expires_at <= now()` rows (seed one expired + one
  live → sweep → live remains) — `LockSweepIT`. `deleteByEntity` releases a usage's lock on delete
  (extend a `NameUsageService`/delete IT or a focused `LockCleanupIT`) and on merge (extend
  `UsageMergeApplyIT`: seed a lock on the merged duplicate → after merge it's gone). Existing
  `LockApiIT` unchanged.
- **Frontend:** `useUsageLock` acquires on `claim()`, heartbeats, releases on unmount (fake timers +
  spied `api/locks`). `TaxonDetail` shows the "locked by X" banner when `listLocks` reports another
  holder and hides it when `heldByMe`/none. `TreeNodeRow`/`NameSearchPage` render the lock icon for a
  locked id. `CurrentWorkPage` lists active locks, shows the empty state, and a held row's Release
  calls `releaseLock` + refetches.

## Decomposition (phases)

1. **Backend activation gaps** — `LockMapper.deleteExpired`/`deleteByEntity` + `LockRetentionSweep` +
   configurable TTL + release-on-delete/merge wiring. (One task; ITs.)
2. **Frontend lock client + edit-lifecycle** — `api/locks.ts` + `useUsageLock` + `TaxonDetail`
   claim-on-edit + the "locked by X" banner.
3. **Frontend surfacing** — the shared `['locks', pid]` query + `TreeNodeRow`/`NameSearchPage` lock
   indicators + the **Activity / Current work** route, page, and sidebar item.

## Out of scope (future)

- **Subtree locking** (lock an accepted taxon + its whole subtree) — designed in the original spec
  (§5.8, "entity_id *or* subtree `path` (ltree)") but never built; needs an ltree/closure column.
  Deferred; this phase is per-name only.
- **Tasks UI** (create/close tasks, tag a lock or an edit session to a task via `X-Task-Id`) — the
  backend is ready; a deliberate "work item" layer is a separate feature. Locks here carry no `taskId`.
- **Locking references / child entities** — `entity_type` generalizes, but this phase locks
  `name_usage` only.
- **Hard (blocking) locks** — never; locks remain advisory, `version` CAS enforces.
- **Force-release of another user's lock by an owner** — rely on TTL + sweep instead.
