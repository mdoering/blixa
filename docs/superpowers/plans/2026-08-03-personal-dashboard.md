# Personal Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A post-login personal dashboard aggregating an attention "inbox" + quick project access + basic metrics across all my projects, plus a live-metrics block folded into each project's Releases tab.

**Architecture:** One backend aggregation endpoint `GET /api/me/dashboard` (+ `POST /api/me/dashboard/seen`) backed by a new `DashboardMapper` of grouped cross-project queries scoped to the caller's `project_member` rows; a `dashboard_seen_at` marker on `app_user` drives the "new pings" count. A live `GET /api/projects/{pid}/metrics` reuses `ReleaseMetricsService`. Frontend: a `DashboardPage` (one query) + a `ProjectMetrics` block on the Releases tab.

**Tech Stack:** Spring Boot / MyBatis / Postgres / Flyway (backend); React + TS + Mantine + TanStack Query + vitest/MSW (frontend). JDK 25 (`sdk env`).

## Global Constraints

- Backend package root `org.catalogueoflife.editor`; MyBatis annotation mappers; schema owned by Flyway (next version **V9**). No secrets in code.
- Commit trailers on every commit:
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`
  `Claude-Session: https://claude.ai/code/session_01VaJsE95RSdqmVXEAjhraeK`
- Backend build: `sdk env` (JDK 25); unit `mvn -o test -Dtest=<FQN>`; IT `mvn -o test-compile failsafe:integration-test failsafe:verify -Dit.test=<FQN>` (Docker via `orb start`).
- Frontend: `npx tsc -b` (type gate), `npx vitest run`, `npm run build`. Mantine Select = `role="textbox"`; `notifications.clean()` afterEach if asserting toasts.
- Do not push; commit only. TDD throughout.

---

## Phase 1 — Dashboard shell (cheap sections) + routing

### Task 1: `dashboard_seen_at` column + AppUser plumbing

**Files:**
- Create: `backend/src/main/resources/db/migration/V9__dashboard_seen.sql`
- Modify: `backend/.../user/AppUser.java` (field + getter/setter), `backend/.../user/AppUserMapper.java` (insert/update already `SELECT *`-mapped; add `touchDashboardSeen`)

**Interfaces:**
- Produces: `AppUser.getDashboardSeenAt(): OffsetDateTime`; `AppUserMapper.touchDashboardSeen(int userId)`.

- [ ] Migration: `ALTER TABLE public.app_user ADD COLUMN dashboard_seen_at timestamptz;`
- [ ] Add `private OffsetDateTime dashboardSeenAt;` + accessors to `AppUser` (map-underscore-to-camel auto-maps the `SELECT *`).
- [ ] Add `@Update("UPDATE app_user SET dashboard_seen_at = now() WHERE id = #{id}") void touchDashboardSeen(int id);`
- [ ] Verify: `mvn -o test-compile`. Commit.

### Task 2: `DashboardMapper` grouped queries (cheap sections)

**Files:**
- Create: `backend/.../dashboard/DashboardMapper.java`
- Test: covered end-to-end in Task 4's `MeDashboardIT`.

**Interfaces (records + queries), all scoped by an `IN` list of the caller's project ids:**
- `record ProjectCount(int projectId, String key, long count)`
- `List<ProjectCount> usageStatusCounts(List<Integer> pids)` — `SELECT project_id, status AS key, COUNT(*) FROM name_usage WHERE project_id IN (...) GROUP BY 1,2`
- `List<ProjectCount> openErrorCounts(List<Integer> pids)` — `... FROM issue WHERE project_id IN (...) AND status='OPEN' AND severity='ERROR' GROUP BY project_id` (key literal `'error'`)
- `List<ProjectCount> reviewCounts(List<Integer> pids)` — `... FROM discussion WHERE project_id IN (...) AND status='REVIEW' GROUP BY project_id`
- `int countPendingUsers()` — `SELECT COUNT(*) FROM app_user WHERE state='PENDING'`
- `record LockRow(int projectId, int usageId, String scientificName, OffsetDateTime acquiredAt)` + `List<LockRow> myLocks(int userId)` — `FROM lock l JOIN name_usage n ON n.project_id=l.project_id AND n.id=l.entity_id WHERE l.user_id=#{userId} AND l.entity_type='name_usage' AND l.expires_at > now()`
- `record RecentTaxon(int projectId, int usageId, String scientificName, OffsetDateTime editedAt)` + `List<RecentTaxon> recentTaxa(@Param("userId") int userId, @Param("pids") List<Integer> pids, @Param("limit") int limit)` — latest `name_usage` edit per usage by me: `SELECT c.project_id, c.entity_id AS usage_id, n.scientific_name, MAX(c.at) AS edited_at FROM change c JOIN name_usage n ON n.project_id=c.project_id AND n.id=c.entity_id WHERE c.user_id=#{userId} AND c.entity_type='name_usage' AND c.project_id IN (...) GROUP BY 1,2,3 ORDER BY edited_at DESC LIMIT #{limit}`

- [ ] Write the mapper interface with the annotated `@Select` queries above (MyBatis `<foreach>`/collection binding for the `IN` list — use `#{pids}` with a provided `List<Integer>`; empty list guarded in the service).
- [ ] `mvn -o test-compile`. Commit (mapper only; exercised by Task 4 IT).

### Task 3: `DashboardService` + `DashboardResponse` + controller (cheap sections)

**Files:**
- Create: `backend/.../dashboard/DashboardService.java`, `backend/.../dashboard/DashboardController.java`, `backend/.../dashboard/dto/DashboardResponse.java`
- Consumes: `CurrentUser.require()`, `ProjectMapper.findByMember`, `ProjectMemberMapper.findRole`, `DashboardMapper` (Task 2), `AppUser.isAdmin()`, `Project` metadata fields.

**Interfaces:**
- Produces: `GET /api/me/dashboard` → `DashboardResponse`; `POST /api/me/dashboard/seen` → 204.
- `DashboardResponse` = record with: `Integer pendingUsers` (null unless admin), `List<ProjectCard> projects`, `List<CountRef> reviewSubmissions`, `List<MissingMeta> missingMetadata`, `List<CountRef> openErrors`, `List<LockRef> myLocks`, `List<TaxonRef> recentTaxa`, `PingSection pings` (Task 5; empty for now).
  - `ProjectCard(int id, String title, String alias, String role, long accepted, long synonyms, long openIssues)`
  - `CountRef(int projectId, String projectTitle, long count)`
  - `MissingMeta(int projectId, String projectTitle, List<String> missing)`
  - `LockRef(int projectId, String projectTitle, int usageId, String scientificName, OffsetDateTime acquiredAt)`
  - `TaxonRef(int projectId, String projectTitle, int usageId, String scientificName, OffsetDateTime editedAt)`

- [ ] Service `build(int userId)`: load my projects; collect `pids`; if empty return an empty response (short-circuit). Fetch grouped counts once; index by projectId. For each project build a `ProjectCard` (accepted = status ACCEPTED count, synonyms = SYNONYM+MISAPPLIED, openIssues from a status-count map — reuse `IssueMapper.countByStatusSeverity` per project OR add an all-open grouped query; use `openErrorCounts` for the error card and a separate open-total for the card). `missingMetadata`: only projects where my role == owner and any of {license, description, geographicScope, taxonomicScope} blank. `reviewSubmissions`: only where role ∈ {owner,editor}. `pendingUsers`: `countPendingUsers()` only if `me.isAdmin()`, else null. `myLocks`, `recentTaxa(limit=8)`.
- [ ] Controller: `@GetMapping("/api/me/dashboard")` and `@PostMapping("/api/me/dashboard/seen")` (calls `users.touchDashboardSeen`), both `currentUser.require()`.
- [ ] `mvn -o test-compile`. Commit.

### Task 4: `MeDashboardIT` (cheap sections)

**Files:** Create `backend/src/test/java/.../dashboard/MeDashboardIT.java` (mirror `TreeApiIT` setup: `createProject`, `createUsage`, MockMvc, `@WithMockUser`).

- [ ] Test: a member sees their project as a `ProjectCard` with correct accepted/synonym headline counts; a non-member's project is absent.
- [ ] Test: `missingMetadata` lists an owned project lacking a license; `openErrors`/`reviewSubmissions` reflect seeded issues/REVIEW discussions.
- [ ] Test: `pendingUsers` is null for a non-admin and a number for an admin.
- [ ] Test: `recentTaxa` returns my most-recently-edited usage first, deduped, and omits usages I never edited.
- [ ] Run: `... failsafe:integration-test failsafe:verify -Dit.test=MeDashboardIT`. Green. Commit.

### Task 5: Pings section (last-visit marker)

**Files:** Modify `DashboardMapper` (+ `pings`), `DashboardService` (populate `pings`), `DashboardResponse` (`PingSection`), `MeDashboardIT` (+ pings tests).

**Interfaces:**
- `record PingRow(int projectId, String projectTitle, int discussionId, String title, String snippet, OffsetDateTime createdAt)`
- `List<PingRow> pings(@Param("userId") int, @Param("orcid") String, @Param("username") String, @Param("pids") List<Integer>, @Param("since") OffsetDateTime, @Param("limit") int)`
- `PingSection(long count, List<PingRow> items)`

- [ ] Query: comments by others on my discussions:
  `SELECT c.project_id, d.title, c.discussion_id, left(c.body, 140) AS snippet, c.created_at, p.title AS project_title FROM discussion_comment c JOIN discussion d ON d.project_id=c.project_id AND d.id=c.discussion_id JOIN project p ON p.id=c.project_id WHERE c.project_id IN (...) AND c.author_id <> #{userId} AND c.created_at > COALESCE(#{since}, 'epoch') AND ( d.author_id = #{userId} OR EXISTS(SELECT 1 FROM discussion_follow f WHERE f.project_id=c.project_id AND f.discussion_id=c.discussion_id AND f.user_id=#{userId}) OR c.body ILIKE '%@'||#{username}||'%' OR (#{orcid} IS NOT NULL AND c.body ILIKE '%@'||#{orcid}||'%') ) ORDER BY c.created_at DESC LIMIT #{limit}` (mention match is text-based — no mention table exists).
- [ ] Service: `since = me.getDashboardSeenAt()`; `pings` = `PingSection(rows.size()-capped? use a COUNT query for the true count, items = limited list)`. Add `long pingCount(...)` (same WHERE, `COUNT(*)`), items via `pings(... limit 5)`.
- [ ] IT: seed a discussion I follow + a comment by another user after my seen marker → count 1; my own comment doesn't count; after `POST /seen`, count resets to 0.
- [ ] Green. Commit.

### Task 6: Frontend API + `DashboardPage` + routing

**Files:**
- Create: `frontend/src/api/dashboard.ts`, `frontend/src/dashboard/DashboardPage.tsx`, `frontend/src/dashboard/DashboardPage.test.tsx`
- Modify: `frontend/src/App.tsx` (route `dashboard`), `frontend/src/auth/useLocalLogin.ts:28` (`/projects` → `/dashboard`), `frontend/src/pages/LandingPage.tsx` (authed → `<Navigate to="/dashboard">`), `frontend/src/components/AppLayout.tsx` (brand link → `/dashboard`).

**Interfaces:**
- `api/dashboard.ts`: `interface Dashboard { pendingUsers: number|null; pings: {count:number; items:PingItem[]}; reviewSubmissions: CountRef[]; missingMetadata: MissingMeta[]; openErrors: CountRef[]; myLocks: LockRef[]; recentTaxa: TaxonRef[]; projects: ProjectCard[] }`; `getDashboard(): Promise<Dashboard>` (`GET /api/me/dashboard`); `markDashboardSeen(): Promise<void>` (`POST /api/me/dashboard/seen`).

- [ ] `api/dashboard.ts` with the types + two functions (mirror `api/projects.ts` over the shared `api()` client).
- [ ] `DashboardPage`: `useQuery(['dashboard'], getDashboard)`; render inbox cards (each hidden when its section is empty/zero; approvals only when `pendingUsers`), the recently-edited card, and project cards with headline counts + links; a `useMutation(markDashboardSeen)` fired once on mount after data loads (invalidate nothing — just resets server count next load).
- [ ] Route: add `<Route path="dashboard" element={<DashboardPage />} />` under `AppLayout`; change local-login + landing redirects; brand link.
- [ ] `DashboardPage.test.tsx` (MSW): renders project card + counts; hides approvals card without `pendingUsers`, shows with; a ping count renders; the seen POST fires. `tsc -b`, `vitest run src/dashboard/`, `npm run build`. Commit.

## Phase 3 — Project metrics on the Releases tab

### Task 7: `GET /api/projects/{pid}/metrics`

**Files:** Create `backend/.../release/ProjectMetricsController.java` (or add to an existing release controller); Test `backend/.../release/ProjectMetricsIT.java`.

**Interfaces:**
- Produces: `GET /api/projects/{pid}/metrics` → the metrics JSON (reuse `ReleaseMetricsService.compute(pid, sinceLastReadyReleaseCreatedAt)`; `since` from `ReleaseMapper.previousReadyCreatedAt`-style lookup, null if none). Any project member may read (`projects.requireRole`).

- [ ] `@GetMapping("/api/projects/{pid}/metrics")`: `requireRole(uid, pid)`; compute `since` = latest READY release created_at (or null); return `metricsService.compute(pid, since)` parsed to a `Map`/`JsonNode` (the service returns a JSON string — parse with the injected `ObjectMapper` so the response is a JSON object, not a string).
- [ ] `ProjectMetricsIT`: seed accepted+synonym usages → response has the status counts; a viewer may read.
- [ ] Green. Commit.

### Task 8: `ProjectMetrics` block on the Releases tab

**Files:** Create `frontend/src/projects/ProjectMetrics.tsx` (+ test); Modify `frontend/src/api/metrics.ts` (new), `frontend/src/projects/ProjectMetadataPage.tsx` (Releases tab visible to all members; metrics block at top; publish/delete stay owner-only).

- [ ] `api/metrics.ts`: `getProjectMetrics(pid): Promise<ProjectMetricsData>` (`GET /api/projects/{pid}/metrics`), typed to the snapshot shape (status counts, byRank, references, typeMaterial, issues by severity, changesSinceRelease).
- [ ] `ProjectMetrics.tsx`: `useQuery(['metrics', pid], ...)`; render status/rank counts + since-release deltas as compact stat groups.
- [ ] `ProjectMetadataPage`: make the `releases` tab render for all members (drop the `isOwner` gate on the tab; keep publish form + delete buttons behind `isOwner`); mount `<ProjectMetrics pid=... />` at the top of the panel.
- [ ] `ProjectMetrics.test.tsx` (MSW): renders counts. `tsc -b`, `vitest run`, `npm run build`. Commit.

## Self-review notes
- Spec coverage: routing (T6), inbox cards approvals/pings/review/missing/errors/locks (T3–T5), recentTaxa (T2–T4, promoted to v1), project cards+headline metrics (T3/T6), seen marker (T1/T5), project metrics on Releases tab (T7–T8). ✓
- The Releases tab visibility widening (owner-only → all members, publish owner-gated) is a deliberate adjustment so all members can read metrics per the spec.
- Mentions are matched by `ILIKE` on the comment body (no mention table exists); documented in T5.
