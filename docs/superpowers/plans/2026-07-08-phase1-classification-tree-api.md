# Phase 1 — Classification Tree API (backend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Backend REST endpoints to browse and edit the parent/child classification tree of **accepted** name usages — lazy-loaded roots/children with child counts, an ancestor breadcrumb, and a cycle-safe move/reparent operation.

**Architecture:** Extends the Plan 1–3 backend (Spring Boot 4.1 / Java 25 / MyBatis / Postgres 17). The tree is the `name_usage.parent_id` self-relation among **accepted** usages only (`status = ACCEPTED`); synonyms attach via `synonym_accepted` and are never tree nodes. Traversal uses `parent_id` + the existing `(project_id, parent_id)` index and **recursive CTEs** (for the ancestor path and the move cycle-check). We deliberately **defer `ltree`/closure-table** materialization — the lazy-children, path, and move operations don't need it; it's only worth adding when full-descendant counts or subtree locking demand it (Plan 5 / phase-4 batch). Committed directly to `main`. Frontend virtualized tree UI is a follow-on plan.

**Tech Stack:** Java 25, Spring Boot 4.1, MyBatis (hand-written SQL), PostgreSQL 17, Testcontainers. Build under JDK 25 (`backend/.sdkmanrc`; `JAVA_HOME=~/.sdkman/candidates/java/25.0.1-librca`).

## Global Constraints

- Base package `org.catalogueoflife.editor`; backend under `backend/`; commit to `main` (no branches). Build with **JDK 25** (the default `java` is 21 and won't compile).
- MyBatis hand-written SQL; project-scoped; **compound `(project_id, id)` keys** (per-project int ids). Reads = any project member; writes (move) = owner or editor (403 else); non-member = 404. Optimistic `version` → 409.
- The tree contains **accepted usages only** — every tree query filters `status = 'ACCEPTED'` (the `Status` enum name). `parent_id` links accepted→accepted; roots have `parent_id IS NULL`.
- Tree nodes carry the raw `scientific_name` + `authorship` + `rank` (the client composes the label) — do **NOT** call the name-parser per node (avoid the per-row re-parse).
- Every task ends green: `cd backend && JAVA_HOME=/Users/markus/.sdkman/candidates/java/25.0.1-librca mvn clean verify` → BUILD SUCCESS, then commit. (No OrbStack workaround under Testcontainers 2.0.)

## File Structure

```
backend/src/main/java/org/catalogueoflife/editor/tree/
  TreeNode.java            # projection record (id, scientificName, authorship, rank, status, ordinal, childCount)
  TreeMapper.java          # roots / children / path / descendant-check SQL
  TreeService.java         # authz + move (cycle check) + optimistic version
  TreeController.java      # /api/projects/{pid}/tree/... + move endpoint
  dto/PathNode.java, MoveRequest.java
backend/src/test/java/org/catalogueoflife/editor/tree/
  TreeApiIT.java
```

---

### Task 1: Tree read endpoints — roots, children, ancestor path

**Files:**
- Create: `backend/src/main/java/org/catalogueoflife/editor/tree/TreeNode.java`, `TreeMapper.java`, `TreeService.java`, `TreeController.java`, `dto/PathNode.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/tree/TreeApiIT.java`

**Interfaces:**
- Produces:
  - `TreeNode` record: `Integer id`, `String scientificName`, `String authorship`, `String rank`, `String status`, `Integer ordinal`, `int childCount`.
  - `TreeMapper`:
    - `List<TreeNode> findRoots(@Param("projectId") int pid, @Param("limit") int limit, @Param("offset") int offset)`
    - `List<TreeNode> findChildren(@Param("projectId") int pid, @Param("parentId") int parentId, @Param("limit") int limit, @Param("offset") int offset)`
    - `List<PathNode> findPath(@Param("projectId") int pid, @Param("id") int id)`
  - `TreeService` (inject `TreeMapper` + `ProjectService`): `listRoots(actorId, pid, limit, offset)`, `listChildren(actorId, pid, parentId, limit, offset)`, `path(actorId, pid, id)` — all require any member (`projects.requireRole`); clamp limit `[1,200]`, offset `>=0` (reuse the existing `Pagination` helper).
  - `TreeController` `@RequestMapping("/api/projects/{pid}/tree")`: `GET /roots`, `GET /children/{parentId}`, `GET /path/{id}` (each `?limit`/`?offset` where applicable).

- [ ] **Step 1: `TreeMapper` SQL** — child count via a scalar subquery; accepted-only; ordered by `ordinal NULLS LAST, scientific_name`.

```sql
-- findRoots
SELECT n.id, n.scientific_name AS scientificName, n.authorship, n.rank, n.status, n.ordinal,
  (SELECT COUNT(*) FROM name_usage c
     WHERE c.project_id = n.project_id AND c.parent_id = n.id AND c.status = 'ACCEPTED') AS childCount
FROM name_usage n
WHERE n.project_id = #{projectId} AND n.parent_id IS NULL AND n.status = 'ACCEPTED'
ORDER BY n.ordinal NULLS LAST, n.scientific_name
LIMIT #{limit} OFFSET #{offset}
```
`findChildren` is identical but `WHERE n.project_id = #{projectId} AND n.parent_id = #{parentId} AND n.status = 'ACCEPTED'`.
`findPath` (recursive CTE, root-first):
```sql
WITH RECURSIVE anc AS (
  SELECT project_id, id, parent_id, scientific_name, rank, 0 AS depth
  FROM name_usage WHERE project_id = #{projectId} AND id = #{id}
  UNION ALL
  SELECT n.project_id, n.id, n.parent_id, n.scientific_name, n.rank, anc.depth + 1
  FROM name_usage n JOIN anc ON n.project_id = anc.project_id AND n.id = anc.parent_id
)
SELECT id, scientific_name AS scientificName, rank FROM anc ORDER BY depth DESC
```
(Note MyBatis maps `scientificName`/`childCount` aliases directly; or rely on underscore→camel with `scientific_name`.)

- [ ] **Step 2:** `TreeNode`/`PathNode` records, `TreeService` (authz + pagination clamp), `TreeController` (the three GETs). Follow the `ReferenceService`/`ReferenceController` authz + pagination pattern already in the codebase.

- [ ] **Step 3: `TreeApiIT`** (extends `AbstractPostgresIT`, MockMvc, owner): seed a project; create accepted usages forming a small tree — e.g. Animalia (root) → Chordata → Mammalia, and a second root Plantae — via the existing `POST /api/projects/{pid}/usages` with `status:"accepted"` and `parentId`. Then assert:
  - `GET /tree/roots` returns Animalia + Plantae, Animalia's `childCount == 1`, Plantae's `childCount == 0`.
  - `GET /tree/children/{animaliaId}` returns Chordata (childCount 1).
  - `GET /tree/path/{mammaliaId}` returns `[Animalia, Chordata, Mammalia]` in root-first order.
  - a synonym (status `synonym`, linked via synonym-of) does NOT appear in roots/children.
  - a non-member gets 404 on `/roots`.

- [ ] **Step 4:** `mvn clean verify` (JDK 25) green; commit `feat(backend): classification tree read API (roots/children/path, accepted-only)`.

---

### Task 2: Move / reparent (cycle-safe)

**Files:**
- Modify: `TreeMapper.java` (descendant check + reparent update), `TreeService.java` (move), `TreeController.java` (move endpoint)
- Create: `backend/src/main/java/org/catalogueoflife/editor/tree/dto/MoveRequest.java`
- Modify: `TreeApiIT.java` (move cases)

**Interfaces:**
- Produces:
  - `MoveRequest` record: `Integer parentId` (nullable → make it a root), `int version` (optimistic check on the moved node).
  - `TreeMapper.isDescendant(@Param("projectId") int pid, @Param("rootId") int rootId, @Param("candidateId") int candidateId)` → boolean (is `candidateId` `rootId` itself or in its subtree?).
  - `TreeMapper.reparent(@Param("projectId") int pid, @Param("id") int id, @Param("parentId") Integer parentId, @Param("version") int version)` → `int` rows (CAS on version).
  - `TreeService.move(actorId, pid, id, MoveRequest)` — owner/editor; the moved usage must be ACCEPTED and in-project (404 else); if `parentId != null`: it must be an ACCEPTED usage in-project (400 else), must not equal `id`, and `isDescendant(id, parentId)` must be false (else 400 "would create a cycle"); then `reparent(...)` — 0 rows → 409.
  - `TreeController`: `PUT /api/projects/{pid}/tree/usages/{id}/parent` (body `MoveRequest`).

- [ ] **Step 1: `isDescendant` SQL** (recursive CTE over the subtree of `rootId`, accepted-only):
```sql
WITH RECURSIVE sub AS (
  SELECT project_id, id FROM name_usage
  WHERE project_id = #{projectId} AND id = #{rootId}
  UNION ALL
  SELECT n.project_id, n.id FROM name_usage n
  JOIN sub ON n.project_id = sub.project_id AND n.parent_id = sub.id
  WHERE n.status = 'ACCEPTED'
)
SELECT EXISTS (SELECT 1 FROM sub WHERE id = #{candidateId})
```
`reparent`: `UPDATE name_usage SET parent_id = #{parentId}, version = version + 1, modified = now() WHERE project_id = #{projectId} AND id = #{id} AND version = #{version}` returning affected rows.

- [ ] **Step 2:** `TreeService.move` (the checks above) + `TreeController` PUT + `MoveRequest`.

- [ ] **Step 3: `TreeApiIT` move cases**: move Mammalia under Plantae → 200; `GET /tree/children/{plantaeId}` now includes Mammalia and `/tree/path/{mammaliaId}` is `[Plantae, Mammalia]`. Moving Animalia under Mammalia (its own descendant) → 400 (cycle). Moving with a stale `version` → 409. A viewer → 403. Moving under a non-accepted (synonym) parent → 400. `parentId: null` → makes the node a root.

- [ ] **Step 4:** `mvn clean verify` (JDK 25) green; commit `feat(backend): cycle-safe tree move/reparent`.

---

## Self-Review Notes

- **Spec coverage (phase-1 tree):** lazy roots/children with child counts (Task 1), ancestor breadcrumb (Task 1), cycle-safe move (Task 2), accepted-only with synonyms excluded (both). Create/rename/change-status already exist via the Plan-3 NameUsage CRUD; synonyms on the detail page via `synonym_accepted` (Plan 3).
- **Deliberate deviation from the spec's `ltree`:** using `parent_id` + recursive CTEs; `ltree`/closure-table deferred until full-descendant counts or subtree locking need it. Recorded here so a reviewer doesn't flag it as a miss.
- **Deferred to follow-ons:** the **frontend virtualized lazy tree UI** (Mantine — expand/collapse fetching children, breadcrumb, drag/"move to", select→detail); full-descendant counts / species estimates; subtree locking (Plan 5); bulk subtree operations (phase-4 batch).
- **Manual verification:** create a small tree via the usages API, then `GET /tree/roots`, drill via `/tree/children/{id}`, `/tree/path/{id}`, and `PUT …/parent` to move a node; confirm cycle/409/403 guards.
