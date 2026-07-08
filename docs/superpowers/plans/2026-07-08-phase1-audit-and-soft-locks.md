# Phase 1 — Audit Log & Soft Locks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An append-only **change log** recording who edited what (with a field-level JSONB diff) across every write path, plus **advisory, auto-expiring soft locks** so collaborators can see when a record is being worked on.

**Architecture:** Extends the Plan 1–4 backend (Spring Boot 4.1 / Java 25 / MyBatis / Postgres 17). Auditing is an explicit service-layer concern: each write service method calls `AuditService.record(...)`, which computes a field-level diff (via Jackson map comparison of before/after) and inserts one `change` row. Locks are a small `lock` table with acquire/refresh/release/list endpoints; a lock is *active* while `expires_at > now()` and is **advisory only** — it never blocks a write (the optimistic `version` check remains the real safety net, per spec §5.9). Committed directly to `main`.

**Tech Stack:** Java 25, Spring Boot 4.1, MyBatis (hand-written SQL), PostgreSQL 17, Jackson (already on the classpath — `tools.jackson`/Jackson 3), Testcontainers. Build under JDK 25.

## Global Constraints

- Base package `org.catalogueoflife.editor`; backend under `backend/`; commit to `main` (no branches). Build with **JDK 25** (`JAVA_HOME=/Users/markus/.sdkman/candidates/java/25.0.1-librca`; default `java` is 21 and won't compile). No OrbStack workaround (Testcontainers 2.0).
- MyBatis hand-written SQL; project-scoped; **compound `(project_id, id)` keys** for domain entities (per-project int ids); `app_user`/`project` have global int ids. Reads = any project member (`ProjectService.requireRole` → 404 if not a member); writes = owner/editor (403 else). The authenticated user is `CurrentUser.require()` (`getId()` → `Integer`).
- **Locks are advisory** — acquiring/holding a lock NEVER blocks another user's write; it only surfaces "someone is working here". Locks **auto-expire** (`expires_at`); an expired lock is treated as absent.
- The `change` log is **append-only** — no update/delete of change rows.
- Migrations: the existing set is `V1__app_user.sql`, `V2__project.sql`, `V3__name_core.sql`. Add new migrations with the next free version numbers (verify with `ls backend/src/main/resources/db/migration/`); do NOT rewrite V1–V3.
- Every task ends green: `cd backend && JAVA_HOME=/Users/markus/.sdkman/candidates/java/25.0.1-librca mvn clean verify` → BUILD SUCCESS, then commit.

## File Structure

```
backend/src/main/java/org/catalogueoflife/editor/audit/
  Change.java              # record: id, projectId, userId, username, at, entityType, entityId, operation, diff(JsonNode/String)
  Operation.java           # enum CREATE, UPDATE, DELETE
  ChangeMapper.java        # insert (append), findByProject, findByEntity
  AuditService.java        # record(projectId, userId, entityType, entityId, op, before, after) -> computes diff, inserts
  ChangeController.java     # GET /api/projects/{pid}/changes
backend/src/main/java/org/catalogueoflife/editor/lock/
  Lock.java, LockMapper.java, LockService.java, LockController.java
  dto/AcquireLockRequest.java, LockResponse.java
backend/src/main/resources/db/migration/V4__change.sql, V5__lock.sql
backend/src/test/java/org/catalogueoflife/editor/audit/ChangeApiIT.java
backend/src/test/java/org/catalogueoflife/editor/lock/LockApiIT.java
```

---

### Task 1: Change log (audit) — record every write + read endpoint

**Files:**
- Create: `V4__change.sql`, `audit/Change.java`, `audit/Operation.java`, `audit/ChangeMapper.java`, `audit/AuditService.java`, `audit/ChangeController.java`
- Modify: `name/ReferenceService.java`, `name/NameUsageService.java`, `tree/TreeService.java`, and the synonymy link/unlink service (wherever `synonym_accepted` rows are created/removed) — add `AuditService.record(...)` calls
- Test: `audit/ChangeApiIT.java`

**Interfaces:**
- Produces:
  - `Operation` enum `{CREATE, UPDATE, DELETE}`.
  - `AuditService.record(int projectId, int userId, String entityType, int entityId, Operation op, Object before, Object after)` — `@Transactional(propagation = MANDATORY)` (runs inside the caller's tx). Computes `diff` and inserts one `change` row. Injected into the write services.
  - `ChangeMapper`: `void insert(Change c)`; `List<Change> findByProject(int projectId, int limit, int offset)`; `List<Change> findByEntity(int projectId, String entityType, int entityId, int limit, int offset)` — both ordered `at DESC, id DESC`, joined to `app_user` for `username`.
  - `Change` record: `Integer id, Integer projectId, Integer userId, String username, OffsetDateTime at, String entityType, Integer entityId, String operation, String diff` (diff as raw JSON string is fine).
  - `ChangeController` `@GetMapping("/api/projects/{pid}/changes")` params `entityType`/`entityId` (both optional; if `entityId` given, `entityType` required), `limit`/`offset` (clamped via `Pagination`); any member.

- [ ] **Step 1: `V4__change.sql`** (append-only; global identity id since the log spans users but is project-scoped):
```sql
CREATE TABLE change (
  id          INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id  INTEGER NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  user_id     INTEGER REFERENCES app_user(id) ON DELETE SET NULL,
  at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  entity_type TEXT NOT NULL,
  entity_id   INTEGER NOT NULL,
  operation   TEXT NOT NULL,           -- CREATE | UPDATE | DELETE
  diff        JSONB
);
CREATE INDEX change_project_at_idx ON change (project_id, at DESC, id DESC);
CREATE INDEX change_entity_idx     ON change (project_id, entity_type, entity_id, at DESC);
```

- [ ] **Step 2: `AuditService.record`** — diff via Jackson (inject the Spring-managed `ObjectMapper`):
  - Convert `before`/`after` to `Map<String,Object>` with `objectMapper.convertValue(obj, Map.class)` (null → empty/absent).
  - `CREATE`: `diff = { "after": {…after fields…} }`. `DELETE`: `diff = { "before": {…before fields…} }`. `UPDATE`: for each key whose value changed, `diff[key] = { "from": oldVal, "to": newVal }` (skip `version`/`modified`/`modifiedBy` churn keys). Serialize `diff` to a JSON string and pass to `ChangeMapper.insert` (bind with `#{diff}::jsonb` or a JSONB type handler consistent with how `project.metadata` jsonb is already handled in this codebase — reuse that approach).
  - Insert the `change` row (`operation = op.name()`).

- [ ] **Step 3: Wire the write paths.** In each service, after the successful mutation (and inside the same `@Transactional`), call `AuditService.record` with `entityType` = `"reference"` / `"name_usage"` / `"synonym_link"`, the actor id, and the correct before/after:
  - `ReferenceService.create` → `record(pid, actor, "reference", id, CREATE, null, saved)`; `update` → `record(..., UPDATE, before, after)` (you already fetch `before` for the version check — reuse it); `delete` → `record(..., DELETE, before, null)`.
  - `NameUsageService.create/update/delete` → same with `"name_usage"`.
  - `TreeService.move` → `record(pid, actor, "name_usage", id, UPDATE, before, after)` capturing the parent change (fetch the row before/after, or record a `{parentId:{from,to}}` diff explicitly).
  - Synonymy link/unlink → `record(pid, actor, "synonym_link", synonymId, CREATE/DELETE, …)` (entityId = the synonym usage id; put the accepted id in the diff).
  Do not change any existing behavior other than adding the audit calls.

- [ ] **Step 4: `ChangeController` + `ChangeMapper` read SQL** (join `app_user` for `username`), any-member gate, pagination clamp.

- [ ] **Step 5: `ChangeApiIT`** (extends `AbstractPostgresIT`, MockMvc): as an owner, create a reference, update its title, create a name usage, move it under a parent; then:
  - `GET /changes` returns the entries newest-first, each with `userId`/`username` = the owner, correct `entityType`/`entityId`/`operation`.
  - the reference-update entry's `diff` contains the title `from`/`to`.
  - `GET /changes?entityType=name_usage&entityId={id}` returns only that usage's entries (create + the move update).
  - a non-member → 404.

- [ ] **Step 6:** `mvn clean verify` (JDK 25) green; commit `feat(backend): append-only change log (audit) across write paths`.

---

### Task 2: Soft locks — acquire / refresh / release / list (advisory, auto-expiring)

**Files:**
- Create: `V5__lock.sql`, `lock/Lock.java`, `lock/LockMapper.java`, `lock/LockService.java`, `lock/LockController.java`, `lock/dto/AcquireLockRequest.java`, `lock/dto/LockResponse.java`
- Test: `lock/LockApiIT.java`

**Interfaces:**
- Produces:
  - `AcquireLockRequest` record: `String entityType`, `int entityId`, `Integer ttlSeconds` (nullable → default 300).
  - `LockResponse` record: `Integer id, String entityType, Integer entityId, Integer userId, String username, OffsetDateTime acquiredAt, OffsetDateTime expiresAt, boolean heldByMe`.
  - `LockService`: `LockResponse acquire(actor, pid, req)`, `LockResponse refresh(actor, pid, lockId, ttl)`, `void release(actor, pid, lockId)`, `List<LockResponse> listActive(actor, pid)`. Any member may hold a lock (owner/editor/reviewer/viewer — reading & signalling intent is not a write). Clamp ttl to `[30, 3600]` seconds.
  - `LockController` `@RequestMapping("/api/projects/{pid}/locks")`: `POST` (acquire), `GET` (list active), `POST /{lockId}/refresh`, `DELETE /{lockId}` (release).

- [ ] **Step 1: `V5__lock.sql`** — one active lock per entity; store as global id, unique per entity:
```sql
CREATE TABLE lock (
  id          INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id  INTEGER NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  entity_type TEXT NOT NULL,
  entity_id   INTEGER NOT NULL,
  user_id     INTEGER NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  acquired_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at  TIMESTAMPTZ NOT NULL,
  UNIQUE (project_id, entity_type, entity_id)
);
CREATE INDEX lock_project_idx ON lock (project_id);
```

- [ ] **Step 2: `LockMapper`** — acquire is an UPSERT that also *steals an expired lock*:
```sql
-- acquire: insert, or take over if the existing lock is expired OR already ours; else no-op
INSERT INTO lock (project_id, entity_type, entity_id, user_id, acquired_at, expires_at)
VALUES (#{projectId}, #{entityType}, #{entityId}, #{userId}, now(), now() + make_interval(secs => #{ttl}))
ON CONFLICT (project_id, entity_type, entity_id) DO UPDATE
  SET user_id = EXCLUDED.user_id, acquired_at = now(), expires_at = EXCLUDED.expires_at
  WHERE lock.user_id = EXCLUDED.user_id OR lock.expires_at <= now()
```
Then a `findByEntity(projectId, entityType, entityId)` to read back the current row and decide the outcome. Also: `findActive(projectId)` = `WHERE project_id = #{projectId} AND expires_at > now()`; `findById(projectId, id)`; `refresh(projectId, id, userId, ttl)` = `UPDATE ... SET expires_at = now()+interval WHERE id=#{id} AND project_id=#{projectId} AND user_id=#{userId} AND expires_at > now()` (returns rows); `delete(projectId, id, userId)` returns rows.

- [ ] **Step 3: `LockService.acquire`** — run the UPSERT, then read the row back: if the current holder is the actor → return it (`heldByMe=true`, 200); if held by ANOTHER user and not expired (the UPSERT's WHERE prevented takeover) → **409 Conflict** with a `LockResponse` body describing the current holder (so the UI can show "X is editing" — advisory, the client may still proceed to edit). `refresh`: 0 rows → 404/409 (not yours or expired). `release`: 0 rows → 404. `listActive`: map to `LockResponse` with `heldByMe` computed against the actor and `username` joined.

- [ ] **Step 4: `LockController`** (the four endpoints) — any member (`requireRole`).

- [ ] **Step 5: `LockApiIT`**: owner + a second member (add via the members API).
  - owner `POST /locks {entityType:"name_usage", entityId:1}` → 200, `heldByMe=true`, `expiresAt` in the future.
  - the second member `POST` the SAME entity → **409** with holder = owner.
  - `GET /locks` lists the one active lock.
  - owner `POST /{id}/refresh` extends `expiresAt`; second member refresh → 404/409.
  - owner `DELETE /{id}` → 204; then the second member can acquire it → 200.
  - an already-expired lock (insert one with `expires_at` in the past via the acquire path using `ttl` then… simplest: assert takeover by acquiring after release) is treated as absent — the second member's acquire succeeds.
  - a non-member → 404 on `POST`.

- [ ] **Step 6:** `mvn clean verify` (JDK 25) green; commit `feat(backend): advisory auto-expiring soft locks`.

---

## Self-Review Notes

- **Spec coverage:** §5.7 change log (Task 1: table, field-level JSONB diff, who/what/when, per-entity + recent views; revert can build on this later). §5.8 locks (Task 2: advisory, auto-expiring, surfaced via list; never hard-blocks). §5.9 optimistic version is already in place (Plan 3/4) and unchanged. Scrutinizer = the entity's `modified_by` (last editor), already stored — the change log gives the full history; no separate field.
- **Deliberate deviations / deferrals:** §5.8's `ltree` subtree-path locks are **deferred** (we deferred `ltree` in Plan 4) — locks are entity-level for now. §5.10 `issue` records + §6 async validation are a **separate later plan** (Plan 6), not this one; Task 1's audit calls are the same change-event source that validation will later consume. Revert (§5.7) is modelled-for but not implemented here.
- **DRY:** one `AuditService.record` + Jackson map-diff serves all entity types; the JSONB bind reuses the existing `project.metadata` jsonb handling — don't invent a second mechanism.
- **Manual verification:** make several edits, `GET /changes` (and filtered by entity) to see the history + diffs; acquire a lock as one user, observe the 409 + holder as another, refresh/release, and confirm an expired lock is re-acquirable.
