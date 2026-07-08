# Phase 1 — Tasks (Work-Sessions): Lock Intent + Changelog Grouping

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A lightweight **task** (work-session) concept — a titled unit of work a user undertakes — that (a) explains lock intent to collaborators and (b) groups changelog entries. Tasks are optional and additive: if none is set, locks and changes are simply ungrouped.

**Architecture:** Extends the Plan 5 audit/lock backend. A `task` row = `{title, description, status, owner, project, timestamps}`. Locks optionally carry a `task_id` (so the lock list shows *why* something is locked); changes are stamped with `task_id` at write time from an **`X-Task-Id` request header** (the client holds the active task and sends it — stateless, no per-write-DTO changes). The changelog is then filterable by task, and the task list carries per-task change counts, giving a grouped view without a bespoke endpoint. Committed directly to `main`.

**Tech Stack:** Java 25, Spring Boot 4.1, MyBatis (hand-written SQL), PostgreSQL 17, Testcontainers. Build under JDK 25.

## Global Constraints

- Base package `org.catalogueoflife.editor`; backend under `backend/`; commit to `main` (no branches). Build with **JDK 25** (`JAVA_HOME=/Users/markus/.sdkman/candidates/java/25.0.1-librca`; default `java` is 21 won't compile). No OrbStack workaround (Testcontainers 2.0).
- MyBatis hand-written SQL; project-scoped. `task`/`change`/`lock` use **global-identity `Integer` ids** scoped by a `project_id` column (matching the existing `change`/`lock` tables — NOT the compound `(project_id,id)` keys used by domain entities). `app_user`/`project` have global ids.
- Authz: reuse `ProjectService.requireRole(actorId, projectId)` → 404 for a non-member. **Any member may create/own a task and hold a lock** (declaring intent is not a data write). Closing/editing a task: the **task owner or a project owner** (else 403). `CurrentUser.require().getId()` → actor `Integer`.
- **Tasks are optional & soft:** absence of `X-Task-Id` / `taskId` just means "ungrouped" — nothing breaks. But a *present* task reference that is invalid (not in this project, or `CLOSED`) is a client error → **400** (surfacing the bug rather than silently dropping the grouping).
- Migrations: existing set is `V1..V5`. Add **`V6__task.sql`** (verify next free version with `ls backend/src/main/resources/db/migration/`); do NOT rewrite earlier migrations.
- Every task ends green: `cd backend && JAVA_HOME=/Users/markus/.sdkman/candidates/java/25.0.1-librca mvn clean verify` → BUILD SUCCESS, then commit.

## File Structure

```
backend/src/main/java/org/catalogueoflife/editor/task/
  Task.java, TaskStatus.java (OPEN, CLOSED)
  TaskMapper.java, TaskService.java, TaskController.java
  CurrentTask.java            # request-scoped: reads+validates X-Task-Id for the current request
  dto/CreateTaskRequest.java, UpdateTaskRequest.java, TaskResponse.java
backend/src/main/resources/db/migration/V6__task.sql
backend/src/test/java/org/catalogueoflife/editor/task/TaskApiIT.java
```
Modified: `audit/AuditService.java`, `audit/ChangeMapper.java`, `audit/ChangeController.java` (Task 2); `lock/*` (Task 3).

---

### Task 1: `task` entity + CRUD + schema

**Files:** Create `V6__task.sql`, `task/Task.java`, `task/TaskStatus.java`, `task/TaskMapper.java`, `task/TaskService.java`, `task/TaskController.java`, `task/dto/CreateTaskRequest.java`, `task/dto/UpdateTaskRequest.java`, `task/dto/TaskResponse.java`, `task/TaskApiIT.java`.

**Interfaces:**
- `TaskStatus` enum `{OPEN, CLOSED}`.
- `CreateTaskRequest(String title, String description)`; `UpdateTaskRequest(String title, String description, String status)` (any field nullable → leave unchanged; `status="closed"` closes it).
- `TaskResponse(Integer id, String title, String description, String status, Integer userId, String username, OffsetDateTime createdAt, OffsetDateTime closedAt, long changeCount)`.
- `TaskMapper`: `void insert(Task t)` (useGeneratedKeys id); `Task findById(int projectId, int id)`; `List<TaskResponse> findByProject(int projectId, String status, int limit, int offset)` (status `null`/`"all"` → no filter; join `app_user` for username; `changeCount` via a `SELECT COUNT(*) FROM change c WHERE c.task_id = t.id` scalar subquery; order `created_at DESC, id DESC`); `int update(...)` (title/description/status/closed_at, scoped `WHERE id AND project_id`).
- `TaskService`: `TaskResponse create(actor, pid, req)` (any member; title required→400 if blank); `List<TaskResponse> list(actor, pid, status, limit, offset)` (any member; `Pagination` clamp); `TaskResponse get(actor, pid, id)` (404 if not in project); `TaskResponse update(actor, pid, id, req)` — task owner or a project owner (else 403); closing sets `status=CLOSED, closed_at=now()`.
- `TaskController` `@RequestMapping("/api/projects/{pid}/tasks")`: `POST` create, `GET` list (`?status`,`?limit`,`?offset`), `GET /{id}`, `PATCH /{id}` (update/close).

- [ ] **Step 1: `V6__task.sql`** — the table plus the FK columns on `change`/`lock` (all columns land here; Tasks 2–3 just populate them):
```sql
CREATE TABLE task (
  id          INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id  INTEGER NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  user_id     INTEGER REFERENCES app_user(id) ON DELETE SET NULL,
  title       TEXT NOT NULL,
  description TEXT,
  status      TEXT NOT NULL DEFAULT 'OPEN',
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  closed_at   TIMESTAMPTZ
);
CREATE INDEX task_project_idx ON task (project_id, status);

ALTER TABLE change ADD COLUMN task_id INTEGER REFERENCES task(id) ON DELETE SET NULL;
CREATE INDEX change_task_idx ON change (project_id, task_id);
ALTER TABLE lock ADD COLUMN task_id INTEGER REFERENCES task(id) ON DELETE SET NULL;
```

- [ ] **Step 2:** `Task`, `TaskStatus`, DTOs, `TaskMapper`, `TaskService`, `TaskController` per the interfaces. Mirror the authz/pagination patterns from `lock/LockService`/`ChangeController`. `status` stored as the enum name; the DTO/response expose lowercase strings — accept `"open"/"closed"` case-insensitively and 400 on anything else.

- [ ] **Step 3: `TaskApiIT`** (extends `AbstractPostgresIT`, MockMvc, owner + a second member via the members API): create a task → 200 with `status:"open"`, `changeCount:0`, owner = actor; list returns it; `?status=open`/`?status=closed` filter correctly; `PATCH {status:"closed"}` sets `closed_at`; a non-owner non-project-owner member → 403 on close; a non-member → 404 on `GET /tasks`.

- [ ] **Step 4:** `mvn clean verify` (JDK 25) green; commit `feat(backend): task (work-session) entity + CRUD`.

---

### Task 2: Attribute changes to a task via `X-Task-Id` + changelog grouping

**Files:** Create `task/CurrentTask.java`. Modify `audit/AuditService.java`, `audit/ChangeMapper.java`, `audit/Change.java` (add `taskId`), `audit/ChangeController.java`. Modify `task/TaskApiIT.java` (or a new `ChangeGroupingIT`).

**Interfaces:**
- `CurrentTask` — a `@RequestScope` bean: `Integer resolve(int projectId)` reads the `X-Task-Id` header from the current request (inject `HttpServletRequest` or use `RequestContextHolder`). If absent/blank → `null`. If present: parse int (unparseable → 400), `TaskMapper.findById(projectId, id)` must exist AND be `OPEN` (else 400 "unknown or closed task"); memoize the result for the request. It resolves against the path's project.
- `AuditService.record(...)`: before inserting, `Integer taskId = currentTask.resolve(projectId)`; set it on the inserted `change` row.
- `ChangeMapper.insert` includes `task_id`; add `List<Change> findByTask(int projectId, int taskId, int limit, int offset)` (ordered `at DESC, id DESC`, joined to `app_user`). `Change` gains `Integer taskId`.
- `ChangeController` `GET /changes` gains an optional `taskId` param → `findByTask` (mutually exclusive-ish with entity filters; if `taskId` present, use it).

- [ ] **Step 1: `CurrentTask`** request-scoped bean (validating resolver above). Inject it into `AuditService`.
- [ ] **Step 2:** `change.task_id` wired through `Change`/`ChangeMapper.insert`; `AuditService.record` stamps `currentTask.resolve(projectId)`. Since `record` runs inside the write transaction, a 400 from an invalid header rolls the whole write back (correct — nothing persists under a bogus task).
- [ ] **Step 3:** `ChangeController` + `ChangeMapper.findByTask` for `?taskId=`. `TaskResponse.changeCount` (Task 1) now reflects stamped changes.
- [ ] **Step 4: IT**: open a task `T`; make two edits (create a reference, update it) sending header `X-Task-Id: T`; make one edit WITHOUT the header. Assert: `GET /changes?taskId=T` returns exactly the two task edits; `GET /tasks/{T}` shows `changeCount:2`; the un-headered change has `taskId:null`; sending `X-Task-Id` = a CLOSED task → the write returns **400** and (re-fetch) did NOT persist; `X-Task-Id` = a foreign/nonexistent id → 400.
- [ ] **Step 5:** `mvn clean verify` green; commit `feat(backend): attribute changes to tasks via X-Task-Id + changelog grouping`.

---

### Task 3: Lock intent — attach a task to a lock

**Files:** Modify `lock/dto/AcquireLockRequest.java` (add `Integer taskId`), `lock/dto/LockResponse.java` (add `Integer taskId`, `String taskTitle`), `lock/Lock.java`, `lock/LockMapper.java`, `lock/LockService.java`. Modify `lock/LockApiIT.java`.

**Interfaces:**
- `AcquireLockRequest` gains `Integer taskId` (nullable). `LockResponse` gains `Integer taskId`, `String taskTitle`.
- `LockService.acquire`: if `taskId != null`, validate it is an `OPEN` task in this project (else 400) and store it on the lock; `findActive`/`findByEntity` join `task` to populate `taskTitle` so the lock list shows intent.

- [ ] **Step 1:** `lock.task_id` through the acquire UPSERT (`lock/LockMapper` — include `task_id` in the INSERT and the `DO UPDATE SET`), `Lock`, and the read queries (LEFT JOIN `task` for `title`).
- [ ] **Step 2:** `LockService.acquire` validates the optional `taskId` (reuse `TaskMapper.findById` + OPEN check; 400 otherwise) and threads it in; `LockResponse` carries `taskId`/`taskTitle`.
- [ ] **Step 3: IT** (extend `LockApiIT`): open a task `T`; acquire a lock with `{entityType:"name_usage", entityId:1, taskId:T}` → 200 with `taskId=T`, `taskTitle="…"`; `GET /locks` shows the title (intent); acquiring with a CLOSED/foreign `taskId` → 400; acquiring with no `taskId` still works (null title).
- [ ] **Step 4:** `mvn clean verify` green; commit `feat(backend): attach task intent to locks`.

---

## Self-Review Notes

- **Covers the request:** lock note/intent (Task 3, via a real task rather than a throwaway free-text column), and the changelog-grouping vision (Tasks 1–2: tasks stamp changes, changelog filters by task, task list carries counts).
- **Additive & soft:** every task linkage is nullable/optional; no existing behavior changes when tasks aren't used. The only hard failure is a *present-but-invalid* task reference → 400 (deliberate — a client bug should surface).
- **Stateless attribution:** the `X-Task-Id` header means zero churn on the existing write DTOs; the client owns "which task am I working under." No server-side per-user session state.
- **Deferred:** task-scoped revert (the `change.task_id` grouping is the foundation); a dedicated "grouped changelog" endpoint (the task list + `?taskId` filter suffice for the UI to group); auto-closing stale tasks; per-user "active task" server memory (rejected in favor of the header).
- **Type consistency:** `task`/`change`/`lock` all use global-identity ids + `project_id` (siblings), NOT compound keys. `TaskStatus.name()` stored as TEXT; API speaks lowercase.
- **Manual verification:** create a task, set `X-Task-Id` while editing, then `GET /changes?taskId=` and `GET /tasks` (counts) to see grouping; acquire a lock with the task and see the intent in `GET /locks`.
