# Work objective = discussion — design

**Date:** 2026-07-24
**Status:** implemented (2026-07-24)

## Problem

There is no way to say "the edits I'm about to make are part of *this* piece of work,"
so the changelog is an undifferentiated stream. The backlog framed this as *"a prominent
top-right selector to pick the active work objective so subsequent tracked changes attach
to it."*

Exploring the code revealed the objective plumbing already exists as the **`task`** entity
("work-session"): every `change` carries `task_id`, every `lock` carries `task_id`, an
`X-Task-Id` request header selects the active task, and `AuditService.record` stamps it. The
frontend simply never sent the header and never surfaced a selector, so every change today
has `task_id = null` and no task-creation UI exists.

But `task` heavily overlaps **`discussion`**, which already has a title/body (**description**),
a `REVIEW/OPEN/RESOLVED/REJECTED` lifecycle (**state**), **comments**, and **linked changes**
(`discussion_change`). The only things `task` adds are two *mechanisms* — auto-attaching the
active work's changes, and being referenced by a lock — not entity features.

## Decision

**Retire `task` entirely. The work objective *is* a discussion.** An "active objective" is
simply an OPEN discussion the user has selected; while it is active, their changes and locks
attach to it. This removes a redundant entity and reuses the discussion's description, state,
comments, and linked-changes for free.

Because `task_id` is null on every existing change and no tasks exist in practice, **there is
no data to migrate** — retiring `task` is a mechanical rename, not a backfill.

### Core principle: an objective is always optional

**"None" is the default and a first-class state.** With no objective selected, nothing changes
about ordinary editing — no `X-Objective-Id` header is sent, `change.discussion_id` and
`lock.discussion_id` stay null, exactly as today. Picking an objective is a deliberate opt-in;
it must never gate doing simple work.

## Model

- **Active objective** — held **client-side, per project, in `localStorage`** (matching the
  repo's `useLocalStorage` idiom; there is no app store/context). Not persisted server-side.
- **Change → objective (1:1):** rename `change.task_id` → **`change.discussion_id`**, a nullable
  FK to `discussion(project_id, id)`. The `api()` client sends **`X-Objective-Id`** on every
  non-GET; a new `CurrentObjective` (@RequestScope) resolves it to an OPEN discussion in the
  project (400 otherwise, which rolls the write back — identical guard to today's `CurrentTask`),
  and `AuditService.record` stamps `change.discussion_id`. This keeps "the objective a change was
  authored under" unambiguous — trivial for the row display and the History grouping.
- **`discussion_change` (M:N) is unchanged** and stays the mechanism for *manually* linking
  arbitrary changes to a discussion as evidence (the existing Linked-changes panel). A discussion's
  full change set is "authored-under (`change.discussion_id`) ∪ manually-linked
  (`discussion_change`)".
- **Lock → objective:** rename `lock.task_id` → **`lock.discussion_id`** (nullable FK to
  discussion). `AcquireLockRequest.taskId` → `discussionId`, validated as an OPEN discussion.

## Backend

**Migration (Flyway V6):**
- On `change`: drop FK `change_task_id_fkey`; rename `task_id` → `discussion_id`; recreate the
  index (`change_task_idx` → `change_discussion_idx` on `(project_id, discussion_id)`); add FK
  `(project_id, discussion_id) → discussion(project_id, id) ON DELETE SET NULL`.
- On `lock`: drop FK `lock_task_id_fkey`; rename `task_id` → `discussion_id`; add FK
  `(project_id, discussion_id) → discussion(project_id, id) ON DELETE SET NULL`.
- Drop `task` table + `task_project_idx`.

**Remove** the whole `task/` package: `Task`, `TaskStatus`, `TaskService`, `TaskMapper`,
`TaskController`, `CurrentTask`, and the task DTOs.

**Add** `discussion/CurrentObjective.java` — `@Component @RequestScope`, header `X-Objective-Id`,
`resolve(int projectId)` → the active discussion id or null; absent/blank → null; present but not
an OPEN discussion in the project → 400. Mirrors the retired `CurrentTask` exactly, against
`discussion` instead of `task`.

**Rewire:**
- `AuditService.record` — resolve via `CurrentObjective`; set `change.discussion_id`.
- `audit/Change` + `ChangeMapper` — `taskId` → `discussionId`; the change *read* also LEFT JOINs
  `discussion` to expose the objective **title** (for "show objective on rows"); `findByTask` →
  `findByDiscussion`. `ChangeController`'s `taskId` query param → `discussionId`.
- `lock/Lock`, `LockMapper` (join `discussion` for `discussion_title`), `LockService`
  (`validateTask` → `validateDiscussion`), `AcquireLockRequest`, `LockResponse`
  (`taskId/taskTitle` → `discussionId/discussionTitle`).

The selector's OPEN-discussion list reuses the existing `GET /discussions` (filter to OPEN;
add a `status` query param if not already present).

## Frontend

- **`api/activeObjective.ts`** — the active objective (a discussion id) per project in
  `localStorage` (key `coldp-active-objective-{pid}`): a `useActiveObjective(pid)` hook for the
  selector and a plain `readActiveObjectiveId(pid)` for the client, sharing one key derivation.
- **`api()` client** — on non-GET, parse the project id from the path and, if an objective is set
  for that project, add `X-Objective-Id`. Per-project keyed, so cross-project safe. No objective →
  no header.
- **`components/ActiveObjectiveSelector.tsx`** — in the `AppLayout` header top-right (shown only
  inside a project, which the layout already knows via `useMatch`). A compact control showing the
  active objective title or **"No objective"** (the default). Dropdown: **None**; the project's
  OPEN discussions; **"New objective…"** (creates an INTERNAL, OPEN discussion inline via the
  existing discussion-create API, then activates it). **Reconciliation:** on load it fetches the
  OPEN discussions and clears the stored id if it is no longer OPEN — a stale id would otherwise
  400 every write.
- **Locks** — `lock/useUsageLock.ts` passes the active objective as `discussionId` when acquiring;
  `api/locks.ts` `acquireLock` accepts it. The Activity view then shows "editing X under ‹objective›"
  (`LockResponse.discussionTitle`).
- **History** — the task filter `<Select>` becomes an **objective** filter over OPEN/known
  discussions; each `ChangeRow` shows its objective title (`change.discussionId` → title). Remove
  `listTasks`; `listChanges`'s `taskId` param → `discussionId`.
- **Types** — `Change.taskId` → `discussionId` (+ `discussionTitle`); `Lock.taskId/taskTitle` →
  `discussionId/discussionTitle`.

## Phasing

- **A — backend refactor:** migration + retire `task` + `CurrentObjective` + rewire
  AuditService/Change/Lock + expose objective titles. Backend suite green.
- **B — the selector:** `activeObjective` + `api()` header + `ActiveObjectiveSelector`
  (None default, pick/create OPEN discussion, reconcile) + lock tagging.
- **C — changelog surfacing:** History objective filter + objective on change rows.

## Testing

- Backend: `CurrentObjective` resolution (absent → null; OPEN → id; closed/unknown → 400);
  `AuditService` stamps `change.discussion_id` from the header and null without it; a lock
  acquired with an objective records `discussion_id` and rejects a non-OPEN one; the migration
  (task gone, FKs repointed). Update/replace every task-referencing test.
- Frontend: `api()` attaches `X-Objective-Id` only when an objective is set and never on GET;
  `activeObjective` localStorage round-trip; the selector lists/creates/clears and **reconciles a
  stale id to None**; History shows and filters by objective.

## Out of scope (v1)

- Close/reopen an objective from the selector (use the existing discussion status controls).
- Server-side / cross-device active objective (client-side only).
- Objectives in `REVIEW` state (OPEN only for v1).
- Folding `change.discussion_id` into `discussion_change` (kept 1:1 for unambiguous provenance).
