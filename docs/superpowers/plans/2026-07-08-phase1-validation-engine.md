# Phase 1 — Async Validation Engine + Issue Lifecycle

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A decoupled validation subsystem that attaches soft, non-blocking `issue` findings to entities via small independent rules, reconciles them idempotently on recompute, exposes a reviewer lifecycle (**open → accepted / rejected → done**), and re-validates automatically after each edit plus on demand.

**Architecture:** Extends the Plan 1–5 backend. Rules are small units (`ValidationRule`) each producing at most one `Finding` per entity; a `ValidationService` builds a per-entity `RuleContext` (the usage + its related data), runs the rules, and **reconciles** the results against stored issues idempotently (a rule replaces its own prior finding for an entity; cleared findings disappear or become `done`; reviewer decisions survive recompute). Validation never blocks a save: it runs **after the write commits** via a Spring `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` hook, and can be run for a whole project on demand (and after phase-2 import). Committed directly to `main`.

**Tech Stack:** Java 25, Spring Boot 4.1, MyBatis, PostgreSQL 17, Spring `@Async`/events, GBIF name-parser (already a dep — reuse `ParsedName.State`/`Rank`), Testcontainers. Build under JDK 25.

## Global Constraints

- Base package `org.catalogueoflife.editor`; backend under `backend/`; commit to `main`. Build with **JDK 25** (`JAVA_HOME=/Users/markus/.sdkman/candidates/java/25.0.1-librca`; default `java` is 21 won't compile). No OrbStack workaround (Testcontainers 2.0).
- MyBatis hand-written SQL; project-scoped. `issue` uses a **global-identity `Integer` id + `project_id`** (sibling of `change`/`lock`/`task`). Domain entities use compound `(project_id, id)` keys.
- **Severity is soft — NOTHING a rule finds ever blocks a save.** `Severity ∈ {INFO, WARNING, ERROR}`.
- **Idempotent:** re-running validation for an entity must converge — a rule replaces its own prior finding; running twice with unchanged data yields the same issue set. One issue per `(project_id, entity_type, entity_id, rule)` (a UNIQUE constraint).
- **Reviewer decisions survive recompute:** an `ACCEPTED`/`REJECTED` issue keeps its status + reviewer while its finding still holds. Lifecycle: new finding → `OPEN`; reviewer accepts → `ACCEPTED` (work to do); reviewer rejects → `REJECTED` (ignore/suppress); an `OPEN`/`REJECTED` finding that clears → **deleted** (disappears); an `ACCEPTED` finding that clears → **`DONE`** (completed work, kept as a record); a `DONE` finding that recurs → back to `OPEN`. Review = owner/editor/reviewer (not viewer); track `reviewer_id` + `reviewed_at`.
- Reads = any member (`ProjectService.requireRole` → 404 non-member). `CurrentUser.require().getId()` → actor `Integer`.
- Migrations: existing set `V1..V6`; add **`V7__issue.sql`** (verify next free via `ls backend/src/main/resources/db/migration/`).
- Every task ends green: `cd backend && JAVA_HOME=/Users/markus/.sdkman/candidates/java/25.0.1-librca mvn clean verify` → BUILD SUCCESS, then commit.

## File Structure

```
backend/src/main/java/org/catalogueoflife/editor/validation/
  Severity.java (INFO,WARNING,ERROR), IssueStatus.java (OPEN,ACCEPTED,REJECTED,DONE)
  Finding.java (record: rule, severity, message, context)
  RuleContext.java (usage + related data the rules read)
  ValidationRule.java (interface)
  rules/UnparsableNameRule.java, SynonymWithoutAcceptedRule.java, MissingPublishedInRule.java,
        DuplicateNameRule.java, YearVsReferenceRule.java   (Task 1 starter set)
  Issue.java, IssueMapper.java
  ValidationService.java   (buildContext + run rules + reconcile)
  IssueService.java, IssueController.java   (Task 2)
  ValidationTrigger.java   (Task 3: AFTER_COMMIT @Async listener)
  dto/IssueResponse.java, ReviewRequest.java
backend/src/main/resources/db/migration/V7__issue.sql
backend/src/test/java/org/catalogueoflife/editor/validation/{RuleTests.java, ValidationReconcileIT.java, IssueApiIT.java, AutoRevalidateIT.java}
```

---

### Task 1: Issue storage + rule framework + starter rules + idempotent reconciliation

**Files:** Create `V7__issue.sql`, all of `validation/` except `IssueService`/`IssueController`/`ValidationTrigger`/dtos; tests `validation/RuleTests.java` (pure unit tests, no DB) + `validation/ValidationReconcileIT.java`.

**Interfaces (Produces):**
- `Severity{INFO,WARNING,ERROR}`, `IssueStatus{OPEN,ACCEPTED,REJECTED,DONE}`.
- `Finding(String rule, Severity severity, String message, Object context)`.
- `RuleContext` — immutable, built once per usage by `ValidationService`: `NameUsage usage()`; `Integer synonymAcceptedCount()`; `Reference publishedInReference()` (nullable); `int duplicateCount()` (other usages in project with the same `scientific_name`+`authorship`). (Fields the Task-1 rules need; later tasks extend it with `parent()`.)
- `ValidationRule` — `String key()`, `Severity severity()`, `Optional<Finding> evaluate(RuleContext ctx)`. Rules are `@Component`s; `ValidationService` injects `List<ValidationRule>`.
- `Issue` record + `IssueMapper`: `findByEntity(projectId, entityType, entityId)` (all statuses), `insert`, `updateFinding(id, severity, message, context)` (keeps status/reviewer), `reopen(id, severity, message, context)` (→OPEN, clears reviewer), `markDone(id)`, `deleteById(id)`, plus reads for Task 2.
- `ValidationService.revalidateUsage(int projectId, int usageId)` and `revalidateProject(int projectId)` (iterates the project's usages).

- [ ] **Step 1: `V7__issue.sql`**
```sql
CREATE TABLE issue (
  id          INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id  INTEGER NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  entity_type TEXT NOT NULL,
  entity_id   INTEGER NOT NULL,
  rule        TEXT NOT NULL,
  severity    TEXT NOT NULL,        -- INFO | WARNING | ERROR
  message     TEXT NOT NULL,
  context     JSONB,
  status      TEXT NOT NULL DEFAULT 'OPEN',   -- OPEN | ACCEPTED | REJECTED | DONE
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  reviewer_id INTEGER REFERENCES app_user(id) ON DELETE SET NULL,
  reviewed_at TIMESTAMPTZ,
  UNIQUE (project_id, entity_type, entity_id, rule)
);
CREATE INDEX issue_project_status_idx ON issue (project_id, status, severity);
CREATE INDEX issue_entity_idx ON issue (project_id, entity_type, entity_id);
```

- [ ] **Step 2: the starter rules** (each a `@Component ValidationRule`; `context` JSON reuses the `project.metadata` JSONB binding pattern):
  - `UnparsableNameRule` (`unparsable_name`, WARNING): `usage.getParseState()` present and NOT equal to `ParsedName.State.COMPLETE.name()` (the parser stores the state string) → finding "scientific name not fully parsed (state=…)". (No related data.)
  - `SynonymWithoutAcceptedRule` (`synonym_without_accepted`, ERROR): `usage.getStatus() ∈ {SYNONYM, MISAPPLIED}` AND `ctx.synonymAcceptedCount() == 0` → "synonym/misapplied name is not linked to any accepted taxon" (features.md: synonyms must point to ≥1 accepted).
  - `MissingPublishedInRule` (`missing_published_in`, INFO): `usage.getPublishedInReferenceId() == null` → "no published_in reference set".
  - `DuplicateNameRule` (`duplicate_name`, WARNING): `ctx.duplicateCount() > 0` → "duplicate scientific name + authorship in project (N other usages)"; `context={count}`.
  - `YearVsReferenceRule` (`year_vs_reference`, WARNING; features.md rule): `usage.getPublishedInYear() != null` AND `ctx.publishedInReference() != null` AND a 4-digit year can be read from the reference (a `getYear()`/`getIssued()` field — inspect `name/Reference.java`; extract `\d{4}` if it's text) AND `abs(year - refYear) > 2` → "authorship year Y differs from reference year Z"; `context={year, referenceYear}`.

- [ ] **Step 3: `ValidationService`** — `buildContext(usage)` runs the few queries (synonym-accepted count via `SynonymAcceptedMapper`; reference via `ReferenceMapper.findByIdInProject`; duplicate count via a new `NameUsageMapper.countDuplicates(projectId, sciName, authorship, excludeId)`). `revalidateUsage`: build context, collect `current = rules.evaluate(...)`, load `existing = issueMapper.findByEntity(...)`, and **reconcile**:
  ```
  for f in current:
    e = existingByRule[f.rule]
    if e == null:                     insert(OPEN, f)
    else if e.status == DONE:         reopen(e.id, f)              // regression -> OPEN, clear reviewer
    else:                             updateFinding(e.id, f)       // OPEN/ACCEPTED/REJECTED keep status+reviewer
  for e in existing where e.rule not in current:                  // finding cleared
    if e.status == ACCEPTED:          markDone(e.id)               // completed work, kept
    else if e.status in (OPEN,REJECTED): deleteById(e.id)         // disappears
    // status DONE stays DONE
  ```
  `revalidateProject` fetches the project's usage ids and calls `revalidateUsage` for each (note in-code: per-usage context loads are O(N) queries — acceptable for on-demand recompute; a set-based fast path is a later optimization).

- [ ] **Step 4: Tests.** `RuleTests` — pure unit tests: construct a `RuleContext` (plain object, no Spring/DB) per rule and assert finding/empty for the boundary cases (e.g. year diff of exactly 2 → OK, 3 → finding; synonym with 0 vs 1 accepted; parse state COMPLETE vs NONE). `ValidationReconcileIT` (extends `AbstractPostgresIT`, uses the real API to seed data, then calls `ValidationService` directly): create a synonym with no accepted link → `revalidateUsage` inserts an `OPEN` `synonym_without_accepted` ERROR; link it to an accepted usage → re-run → the issue is **deleted** (cleared, was OPEN); re-create the problem, `ACCEPT` it (via mapper), clear it → re-run → status becomes `DONE`; run twice unchanged → identical issue set (idempotent).

- [ ] **Step 5:** `mvn clean verify` green; commit `feat(backend): validation engine core — issue model, rule framework, starter rules, idempotent reconcile`.

---

### Task 2: Issue API — list/filter + review lifecycle + on-demand revalidate

**Files:** Create `validation/IssueService.java`, `validation/IssueController.java`, `validation/dto/IssueResponse.java`, `validation/dto/ReviewRequest.java`; modify `IssueMapper` (read queries). Test `validation/IssueApiIT.java`.

**Interfaces (Produces):**
- `IssueResponse(id, entityType, entityId, rule, severity, message, context, status, createdAt, updatedAt, reviewerId, reviewerUsername, reviewedAt)`.
- `ReviewRequest(String action)` — `action ∈ {accept, reject, reopen}`.
- `IssueMapper`: `findByProject(projectId, status, severity, entityType, limit, offset)` (all filters optional; join `app_user` for reviewer username; order `severity` then `created_at DESC`); `countByStatusSeverity(projectId)` for a summary; `findById(projectId, id)`; `review(id, status, reviewerId)` (sets status + reviewer_id + reviewed_at=now + updated_at).
- `IssueService`: `list(actor, pid, filters, page)` (any member); `summary(actor, pid)` (counts by severity/status); `review(actor, pid, issueId, action)` — owner/editor/reviewer (403 for viewer); `accept→ACCEPTED`, `reject→REJECTED`, `reopen→OPEN` (clear reviewer); 404 if the issue isn't in the project. `revalidateProject(actor, pid)` — owner/editor triggers a full project recompute (calls `ValidationService.revalidateProject`), returns the resulting summary.
- `IssueController` `@RequestMapping("/api/projects/{pid}/issues")`: `GET` (list, filters + pagination), `GET /summary`, `POST /{id}/review` (body `ReviewRequest`), and `POST /api/projects/{pid}/revalidate` (on-demand full recompute — features.md "regenerate on demand, e.g. after a deploy").

- [ ] **Step 1:** `IssueMapper` read/review SQL; `IssueService` (authz + pagination clamp + review transitions); `IssueController`. `severity`/`status` API strings are lowercase; store/compare the enum names (case-insensitive parse, 400 on unknown filter value).
- [ ] **Step 2: `IssueApiIT`**: seed data producing a couple of issues (via `POST /revalidate`); `GET /issues` lists them; filter by `?severity=error` and `?status=open`; `POST /{id}/review {action:"reject"}` → status `rejected`, reviewer=actor, `reviewedAt` set; a re-`revalidate` keeps the rejected issue rejected (survives recompute while the finding holds); `reopen` → `open` with reviewer cleared; a viewer → 403 on review; a non-member → 404 on `GET /issues`; `GET /summary` returns counts.
- [ ] **Step 3:** `mvn clean verify` green; commit `feat(backend): issue API — list/filter, review lifecycle (open/accepted/rejected/done), on-demand revalidate`.

---

### Task 3: Auto-revalidate after each write (decoupled, non-blocking)

**Files:** Create `validation/ValidationTrigger.java` + a small `ValidationEvent` record; modify the write services (`ReferenceService`, `NameUsageService`, `TreeService`) to publish an event; async config. Test `validation/AutoRevalidateIT.java`.

**Interfaces:**
- `ValidationEvent(int projectId, String entityType, int entityId)`.
- Write services publish a `ValidationEvent` (via `ApplicationEventPublisher`) for the affected usage on create/update/delete/move (and, when a `reference` changes, for the usages citing it — a bounded related-set: `NameUsageMapper.findIdsByPublishedInReference(projectId, refId)`).
- `ValidationTrigger` — `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async`: calls `ValidationService.revalidateUsage(...)` in a fresh transaction, AFTER the write committed, off the request thread — so a save is never blocked and a rolled-back write triggers no validation. Enable `@EnableAsync` with a small bounded pool (`ThreadPoolTaskExecutor`); name the config so it's testable.

- [ ] **Step 1:** `@EnableAsync` config + executor; `ValidationEvent`; `ValidationTrigger` (`@TransactionalEventListener(phase=AFTER_COMMIT)` + `@Async`, resilient — a rule exception is logged, never propagated to the user).
- [ ] **Step 2:** publish events from the write services (usage create/update/delete, tree move, and reference update → citing usages). Reuse the existing `@Transactional` boundary — publish inside it so AFTER_COMMIT fires only on success.
- [ ] **Step 3: `AutoRevalidateIT`**: create a synonym with no accepted link via the API (no manual `revalidate` call), then **await** (poll with a timeout, e.g. Awaitility or a bounded `GET /issues` retry loop — async) until `GET /issues` shows the `synonym_without_accepted` ERROR; then link it to an accepted usage via the API and await until the issue clears. (Make the async wait bounded + deterministic; don't assert synchronously right after the write.)
- [ ] **Step 4:** `mvn clean verify` green; commit `feat(backend): auto-revalidate affected entities after each write (async, non-blocking)`.

---

## Self-Review Notes

- **Covers:** spec §5.10 (`issue`) + §6 (async, idempotent, soft severity, on-demand + event-driven, per-project problems view via `GET /issues`/`/summary`) and features.md (the two year rules; the open/accepted/rejected/done lifecycle with reviewer + datetime; regenerate on demand). Rule catalogue starts small and grows.
- **Deferred (later rule-catalogue growth / plans):** the related-data rules needing the tree (`rank_vs_parent`, `genus_token_vs_parent`, `infraspecific_part_vs_parent`, `genus_year_after_species`) and `synonym_to_non_accepted` (add once `RuleContext` carries `parent()`/accepted-target status); reusing GBIF/CLB validation logic wholesale; tree issue-count roll-up badges (a read-side aggregate over `issue` — frontend/query concern); a durable event queue (v1 uses AFTER_COMMIT `@Async` — a crash between commit and async loses one recompute, recoverable via on-demand `revalidate`); set-based fast path for `revalidateProject`.
- **Idempotence is the core invariant** — Task 1 Step 4 explicitly tests "run twice unchanged → identical issues" and the reviewer-decision-survives-recompute behavior.
- **Never blocks a save** — validation is AFTER_COMMIT + async (Task 3); the on-demand endpoint (Task 2) is the only synchronous entry and it's an explicit user action.
- **Manual verification:** create a synonym with no accepted link → `GET /issues` shows the ERROR (auto, after a beat); reject it → it stays rejected across `POST /revalidate`; fix it → it clears (or goes `done` if it had been accepted).
