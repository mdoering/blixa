# Frontend — Classification Tree + Taxon Detail (first editor slice)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The core editor view — a two-pane page: a lazy classification **tree** on the left, and a **taxon detail** panel on the right that views and edits the selected name usage's fields, with its synonyms and validation issues. Browse + select + edit; create-child / move / change-status-workflow are deferred.

**Architecture:** React 18 + TypeScript + Vite + Mantine 7 SPA (existing `frontend/`). New API client modules over the existing `api()` helper (`frontend/src/api/client.ts` — handles method/form/JSON + CSRF + `credentials: include`). TanStack Query for server state (roots/children/path/usage/issues/synonyms). A custom lazy tree (each node lazily fetches its children on expand — not Mantine's static `Tree`), selection lifted to the page, detail edited via a Mantine `useForm` with optimistic-version save (409 → refetch + warn). One small **backend prerequisite** (Task 1) adds the missing "list a taxon's synonyms / a synonym's accepted targets" endpoints.

**Tech Stack:** React 18, TypeScript, Vite, Mantine 7.17 (`@mantine/core|form|notifications`), `@tabler/icons-react`, TanStack Query 5, react-router-dom 6, Vitest + Testing Library + MSW (existing test setup). Backend: Spring Boot 4.1 / Java 25 (Task 1 only).

## Global Constraints

- Commit to `main` (no branches). Frontend green = `cd frontend && npm run test` (Vitest) AND `npm run build` (tsc + vite) both pass. Backend (Task 1) green = `cd backend && JAVA_HOME=/Users/markus/.sdkman/candidates/java/25.0.1-librca mvn clean verify` BUILD SUCCESS (JDK 25; default java 21 won't compile).
- Reuse the existing patterns: `api<T>(path, {method, form})` for calls; TanStack Query (`QueryClientProvider` already set up in `main.tsx`); Mantine components + `@tabler/icons-react`; MSW handlers in `src/test/server.ts` + `renderWithProviders` from `src/test/utils.tsx`. Do NOT introduce new libraries.
- Backend base for reads = any project member. The SPA already handles auth/CSRF; new calls go through `api()`.
- **Deferred (not this slice):** tree virtualization (lazy-per-node is enough for now — note it in code), create-child, move/reparent, the acc↔syn status-change + taxon-info-migration workflow, supporting entities. Changing `status` in the detail form is a plain field edit here; the guided migration workflow comes later.

## Existing API (consumed)

- `GET /api/projects/{pid}/tree/roots?limit&offset` and `…/tree/children/{parentId}?limit&offset` → `[{id, scientificName, authorship, rank, status, ordinal, childCount}]`.
- `GET /api/projects/{pid}/tree/path/{id}` → `[{id, scientificName, rank}]` (root-first).
- `GET /api/projects/{pid}/usages/{id}` → `NameUsageResponse` (id, parentId, status, scientificName, authorship, rank, atomized fields, nomStatus, publishedInReferenceId, publishedInYear, publishedInPage, publishedInPageLink, gender, etymology, nameType, parseState, link, version, …). `PUT /api/projects/{pid}/usages/{id}` (body includes `version` for optimistic locking → 409 on stale).
- `GET /api/projects/{pid}/issues?entityType=name_usage&entityId={id}` → issues for the usage.
- **Added in Task 1:** `GET /api/projects/{pid}/usages/{id}/synonyms` and `GET /api/projects/{pid}/usages/{id}/accepted`.

---

### Task 1 (backend): expose synonym relations for a usage

**Files:** Modify `name/NameUsageController.java`, `name/NameUsageService.java`, `name/SynonymAcceptedMapper.java` (add finders if missing), `name/dto/NameUsageResponse.java` reused. Test: `name/SynonymRelationsApiIT.java` (or extend `NameUsageApiIT`).

**Interfaces:**
- `GET /api/projects/{pid}/usages/{id}/synonyms` → `List<NameUsageResponse>` — the usages linked to `{id}` as synonyms (i.e. `synonym_accepted.accepted_usage_id = {id}` → the synonym usages), ordered by scientific name. Any member; 404 if `{id}` not in project.
- `GET /api/projects/{pid}/usages/{id}/accepted` → `List<NameUsageResponse>` — the accepted usages that `{id}` points to (i.e. `synonym_accepted.synonym_id = {id}` → the accepted usages). Any member.
- `SynonymAcceptedMapper`: reuse/add `findSynonymUsageIdsOf(projectId, acceptedId)` and `findAcceptedUsageIdsOf(projectId, synonymId)` (returning the related usage ids); the service loads the `NameUsage`s via `NameUsageMapper.findByIdInProject` and maps to `NameUsageResponse` (reuse the existing mapping used by `GET /usages/{id}`).

- [ ] **Step 1:** the two mapper finders + `NameUsageService.listSynonyms(actor, pid, id)` / `listAccepted(actor, pid, id)` (requireRole; 404 if the anchor usage isn't in project) + the two controller GETs.
- [ ] **Step 2: IT** — build an accepted usage A with two synonyms S1, S2 (link via `PUT /usages/{s}/synonym-of/{a}`); `GET /usages/{A}/synonyms` returns S1+S2; `GET /usages/{S1}/accepted` returns A; a pro parte synonym linked to two accepteds appears under both; non-member → 404.
- [ ] **Step 3:** `mvn clean verify` (JDK 25) green; commit `feat(backend): list a usage's synonyms / accepted targets`.

---

### Task 2 (frontend): tree page + lazy classification tree + breadcrumb

**Files:** Create `src/api/tree.ts`, `src/api/usages.ts`, `src/api/issues.ts` (+ types in `src/api/types.ts`); `src/tree/TreePage.tsx`, `src/tree/ClassificationTree.tsx`, `src/tree/TreeNodeRow.tsx`, `src/tree/Breadcrumb.tsx`; wire a **Tree** route + nav link into the existing project layout/router (follow the `ProjectMetadataPage`/`MembersPage` nav pattern). Tests: `src/tree/ClassificationTree.test.tsx`, `src/tree/TreePage.test.tsx`.

**Interfaces (Produces):**
- `src/api/tree.ts`: `getRoots(pid, {limit,offset})`, `getChildren(pid, parentId, {limit,offset})`, `getPath(pid, id)` → typed on `TreeNode`/`PathNode`.
- `src/api/types.ts`: `TreeNode {id, scientificName, authorship, rank, status, ordinal, childCount}`, `PathNode {id, scientificName, rank}`.
- `TreePage` owns `selectedId` state; renders a two-pane Mantine layout (e.g. `Grid`/`Flex`, left ~40% scrollable tree, right detail). Renders `Breadcrumb` (from `getPath(selectedId)`) above the detail.
- `ClassificationTree` — fetches roots (TanStack Query), renders `TreeNodeRow`s; a row shows an expand chevron when `childCount > 0`, the label (`scientificName` + dimmed `authorship`), and a small rank badge; clicking the label calls `onSelect(id)`; clicking the chevron toggles expansion, and an expanded node lazily fetches + renders its children (each child is another `TreeNodeRow`, recursive). Selected row highlighted. Keep it simple; **no virtualization yet** (code comment noting large sibling lists paginate / virtualization is a follow-up).

- [ ] **Step 1:** `api/tree.ts` + `api/types.ts` types; a unit-ish test that `getRoots`/`getChildren` call the right URLs (MSW).
- [ ] **Step 2:** `TreeNodeRow` + `ClassificationTree` (lazy expand). Test (MSW): mock roots (Animalia childCount 1, Plantae childCount 0) → both render; Plantae has no chevron; click Animalia's chevron → mocked children (Chordata) load and render; click a label → `onSelect` fires with the id.
- [ ] **Step 3:** `TreePage` two-pane shell + `Breadcrumb` (mock `getPath`) + route/nav wired. Test: selecting a node shows the breadcrumb path; the right pane shows the selected node (detail panel placeholder until Task 3, e.g. renders the id/name).
- [ ] **Step 4:** `npm run test` + `npm run build` green; commit `feat(frontend): classification tree page (lazy browse + breadcrumb)`.

---

### Task 3 (frontend): taxon detail panel — view + edit + synonyms + issues

**Files:** Create `src/tree/TaxonDetail.tsx`, `src/tree/SynonymList.tsx`, `src/tree/IssueList.tsx`; extend `src/api/usages.ts` (`getUsage`, `updateUsage`, `getSynonyms`, `getAccepted`), `src/api/issues.ts` (`getEntityIssues`). Tests: `src/tree/TaxonDetail.test.tsx`.

**Interfaces (Produces):**
- `api/usages.ts`: `getUsage(pid, id) → NameUsage`, `updateUsage(pid, id, payload) → NameUsage` (payload carries `version`), `getSynonyms(pid, id)`, `getAccepted(pid, id)`. `api/issues.ts`: `getEntityIssues(pid, entityType, entityId) → Issue[]`. Types `NameUsage`, `Issue` in `types.ts`.
- `TaxonDetail({pid, usageId})` — loads the usage (TanStack Query, keyed on id). A Mantine `useForm` seeded from the usage edits the human-facing fields: `scientificName`, `authorship`, `rank`, `status` (Select: accepted/synonym/misapplied/unassessed), `publishedInYear` (number), `publishedInPage`, `publishedInPageLink`, `nomStatus`, `etymology`, `link`. Show read-only derived fields (`nameType`, `parseState`, atomized genus/epithets) in a muted "parsed" section. **Save** → `updateUsage` with the loaded `version`; on success invalidate the usage + tree queries + notify "Saved"; on **409** (`ApiError.status === 409`) notify "Changed by someone else — reloading" and refetch. Role-gate: disable the form when the user can't edit (reuse the project role the app already exposes — mirror `ProjectMetadataPage`'s `canEdit`).
- `SynonymList` — if the usage is `accepted`, list its synonyms (`getSynonyms`); if it's a `synonym`/`misapplied`, show its accepted target(s) (`getAccepted`). Read-only list for now (link/unlink is a later slice).
- `IssueList` — `getEntityIssues(pid, 'name_usage', id)`; render each with a severity badge (info/warning/error) + message; empty state "No issues".

- [ ] **Step 1:** `api/usages.ts` + `api/issues.ts` + types.
- [ ] **Step 2:** `TaxonDetail` form (view + edit + save + 409 handling + role-gate). Test (MSW): loads a usage → fields prefilled; edit `authorship` + Save → PUT sent with the `version`; a 409 response → shows the conflict notice and refetches; a viewer role → form disabled.
- [ ] **Step 3:** `SynonymList` + `IssueList` wired into `TaxonDetail`. Test: an accepted usage with two mocked synonyms renders both; a usage with a mocked warning issue shows the warning badge + message.
- [ ] **Step 4:** replace the Task-2 detail placeholder in `TreePage` with `TaxonDetail`; `npm run test` + `npm run build` green; commit `feat(frontend): taxon detail panel — edit fields, synonyms, issues`.

---

## Self-Review Notes

- **Covers the approved slice:** two-pane tree + detail, browse/select/edit fields, synonyms + issues shown. Backend gap (list synonyms) filled in Task 1.
- **Deferred (recorded):** virtualization; create-child, move/reparent, sibling reordering; the guided acc↔syn status-change + taxon-info migration (features.md) — here `status` is a plain editable field; link/unlink synonyms from the UI; supporting entities; issue review actions from this panel (the Issues dashboard slice can own accept/reject).
- **Optimistic locking is surfaced, not hidden:** the detail save sends the read `version` and handles 409 with a reload + notice, matching the backend contract.
- **Manual verification:** run locally (see README), open a project → Tree, expand nodes, select a taxon, edit a field + Save (watch it persist + the tree refresh), see its synonyms and any validation issues; open the same taxon in two tabs and save both to see the 409 conflict notice.
