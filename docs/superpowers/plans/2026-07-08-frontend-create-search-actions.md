# Frontend — Name Creation, Search Table & Action Menus

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users **create** name usages (root / child / synonym), act on them via a compact **⋮ + right-click menu** (add child, add synonym, change status, delete), and find them via a **flat search table** (text + rank + status filters) — with both the tree and the table selecting a name into the shared detail panel.

**Architecture:** Extends the tree+detail slice. One reusable `NameActionMenu` (Mantine `Menu`, opened by a ⋮ `ActionIcon` and by right-click) + one `CreateNameModal` are shared by the Tree view and a new **Names** search view. The search table is Mantine-React-Table with server-side pagination/filtering over an extended `GET /usages` (adds `rank`/`status` filters + a total count). Both views render the existing `TaxonDetail` on the right. Committed to `main`.

**Tech Stack:** React 18 + TS + Vite + Mantine 7.17 (`@mantine/core|form|modals|notifications`), `mantine-react-table` 2.0.0-beta.9, `@tabler/icons-react`, TanStack Query 5, react-router-dom 6, Vitest + Testing Library + MSW. Backend (Task 1): Spring Boot 4.1 / Java 25.

## Global Constraints

- Commit to `main`. Frontend green = `cd frontend && npm run test` AND `npm run build`. Backend green = `cd backend && JAVA_HOME=/Users/markus/.sdkman/candidates/java/25.0.1-librca mvn clean verify` (JDK 25; default java 21 won't compile). No OrbStack workaround (Testcontainers 2.0).
- Reuse existing pieces: `api()` client; TanStack Query; Mantine + `@mantine/modals` (ModalsProvider is already mounted in `main.tsx`); `@tabler/icons-react`; `mantine-react-table` (already used in `MembersPage`); MSW test setup (`src/test/server.ts`, `renderWithProviders`); the existing `TaxonDetail`, `ClassificationTree`/`TreeNodeRow`, `TreePage`, and `api/usages.ts`/`api/tree.ts`. **No new libraries.**
- Backend already has: `POST /usages` (create, `CreateNameUsageRequest` with `scientificName, authorship, rank, status, parentId, …`), `PUT /usages/{id}` (update), `DELETE /usages/{id}`, `PUT|DELETE /usages/{id}/synonym-of/{acceptedId}`, `GET /usages?q=` (pg_trgm fuzzy). **Create/delete/link already work server-side — this is mostly a UI gap.**
- **Actions v1:** Add child, Add synonym, Change status, Delete. **Move/reparent is deferred** (backend supports it; the target-picker UI is a later slice). Writes are owner/editor (the backend enforces; UI hides the menu / disables `＋New` for non-editors).
- Status/rank wire form is the **enum name** (e.g. `ACCEPTED`, `SPECIES`) — matches `TaxonDetail`'s existing `STATUS_OPTIONS` and `NameUsageResponse`. Do NOT lowercase (that was only the project nomCode/license case).

## File Structure

```
frontend/src/names/NameActionMenu.tsx      # ⋮ + right-click menu (shared)
frontend/src/names/CreateNameModal.tsx     # create root/child/synonym (shared)
frontend/src/names/NameSearchPage.tsx      # Names tab: filter table + shared detail
frontend/src/names/useNameActions.ts       # create/delete/link/status mutations + query invalidation (shared hook)
frontend/src/api/usages.ts                 # + createUsage, deleteUsage, linkSynonym, searchUsages
backend: name/NameUsageController|Service|Mapper (Task 1), name/dto/UsagePage.java
```

---

### Task 1 (backend): search filters (rank, status) + total count

**Files:** Modify `name/NameUsageController.java`, `name/NameUsageService.java`, `name/NameUsageMapper.java`; create `name/dto/UsagePage.java`. Test: extend `NameUsageApiIT`.

**Interfaces:**
- `UsagePage(List<NameUsageResponse> items, long total)`.
- `GET /api/projects/{pid}/usages?q=&rank=&status=&limit=&offset=` → `UsagePage`. `q` fuzzy (existing pg_trgm `scientific_name % q`); `rank`/`status` are optional exact filters (enum names, case-insensitive → 400 on an unknown value, reuse the existing tolerant enum parsing); combine with AND. `total` = count of all matches (ignoring limit/offset) for the same filters.
- `NameUsageService.searchPage(uid, pid, q, rank, status, limit, offset) → UsagePage` (requireRole; Pagination clamp).
- `NameUsageMapper`: `searchItems(...)` + `countMatches(...)` (or a `<script>` mapper with optional `<if>` clauses for q/rank/status shared by both). When `q` blank → order by `scientific_name`; when present → order by `similarity(scientific_name, q) DESC`.

- [ ] **Step 1:** the mapper filter/count SQL (optional `q`/`rank`/`status`), `UsagePage`, `NameUsageService.searchPage`, controller returns `UsagePage`. (The list/no-q path folds into the same query with all filters null.)
- [ ] **Step 2: IT** — seed usages of mixed rank+status; assert: `?rank=SPECIES` returns only species (+ correct `total`); `?status=SYNONYM` only synonyms; `?q=` fuzzy still works; combined `?q=&rank=&status=` ANDs; an unknown `rank`/`status` → 400; `total` reflects all matches while `limit` caps `items`.
- [ ] **Step 3:** `mvn clean verify` (JDK 25) green; commit `feat(backend): usage search with rank/status filters + total count`.

---

### Task 2 (frontend): create flow + ⋮/right-click action menu, wired into the Tree

**Files:** Create `src/names/NameActionMenu.tsx`, `src/names/CreateNameModal.tsx`, `src/names/useNameActions.ts`; extend `src/api/usages.ts` (`createUsage`, `deleteUsage`, `linkSynonym`). Modify `src/tree/TreeNodeRow.tsx` (add the ⋮ + right-click), `src/tree/TreePage.tsx` (toolbar `＋New`). Tests: `src/names/NameActionMenu.test.tsx`, `src/names/CreateNameModal.test.tsx`.

**Interfaces (Produces):**
- `api/usages.ts`: `createUsage(pid, {scientificName, authorship?, rank?, status, parentId?}) → NameUsage`; `deleteUsage(pid, id) → void`; `linkSynonym(pid, synonymId, acceptedId) → void` (`PUT /usages/{synonymId}/synonym-of/{acceptedId}`).
- `useNameActions(pid)` — a hook returning mutation helpers that also invalidate `['treeRoots',pid]`/`['treeChildren',pid]`/`['usageSearch',pid]` and notify: `createChild(parent)`, `createSynonymOf(accepted)`, `createRoot()`, `changeStatus(usage, status)` (PUT with the usage's `version`), `remove(usage)`.
- `CreateNameModal({pid, mode, anchor, onCreated})` — `mode ∈ {root, child, synonym}`; shows the context ("Child of *Abies*" / "Synonym of *Abies*" / "New root name"); fields: `scientificName` (required), `authorship`, `rank` (Select of the common ranks). Submit: `createUsage` with `parentId = anchor.id` + `status='ACCEPTED'` for child; `status='ACCEPTED', parentId=null` for root; for synonym → `createUsage({status:'SYNONYM'})` then `linkSynonym(new.id, anchor.id)`. On success: close, invalidate, notify, and `onCreated(newId)` (so the caller selects it). Opened via `@mantine/modals` `modals.open` or a controlled `<Modal>`.
- `NameActionMenu({usage, canEdit, onSelect, pid})` — a Mantine `Menu` whose target is a ⋮ `ActionIcon`; items: **Add child**, **Add synonym** (both open `CreateNameModal`), **Change status ▸** (submenu: Accepted/Synonym/Misapplied/Unassessed → `changeStatus`), **Delete** (confirm via `modals.openConfirmModal` → `remove`). Hidden/disabled when `!canEdit`. The row also wires `onContextMenu` (preventDefault) to open the same menu.

- [ ] **Step 1:** `api/usages.ts` additions + `useNameActions` hook.
- [ ] **Step 2:** `CreateNameModal` + test (MSW): open in `child` mode for anchor "Abies" → submit "Abies alba" → POST body has `parentId=anchor`, `status:'ACCEPTED'`; synonym mode → POST `status:'SYNONYM'` then the synonym-of PUT fires; `onCreated` called with the new id.
- [ ] **Step 3:** `NameActionMenu` + test: renders the actions; "Add child" opens the modal in child mode; "Delete" opens a confirm then calls DELETE; a `canEdit=false` menu is not shown. Then wire it into `TreeNodeRow` (⋮ on hover/selected + `onContextMenu`) and add a `＋ New name` toolbar button in `TreePage` (opens `CreateNameModal` in `root` mode, or `child` of the selected node if one is selected). Creating refreshes the tree and selects the new node.
- [ ] **Step 4:** `npm run test` + `npm run build` green; commit `feat(frontend): create names + ⋮/right-click action menu in the tree`.

---

### Task 3 (frontend): Names search table view

**Files:** Create `src/names/NameSearchPage.tsx`; extend `src/api/usages.ts` (`searchUsages`); add the **Names** route + nav tab (mirror the Tree tab in the project layout). Test: `src/names/NameSearchPage.test.tsx`.

**Interfaces (Produces):**
- `api/usages.ts`: `searchUsages(pid, {q?, rank?, status?, limit, offset}) → {items: NameUsage[], total: number}`.
- `NameSearchPage` — a two-pane layout: left/main a `mantine-react-table` with **manual (server-side) pagination + filtering**; columns: scientific name (+ dimmed authorship), rank, status; toolbar filters: a text search (`q`, debounced), a `rank` Select, a `status` Select; each row has the `NameActionMenu` (⋮/right-click) and clicking a row sets `selectedId`; right pane renders the shared `TaxonDetail` for `selectedId`. A `＋ New name` toolbar button (root mode) via `CreateNameModal`. Query keyed `['usageSearch', pid, {q,rank,status,page,size}]`; `rowCount` = `total`.

- [ ] **Step 1:** `searchUsages` + the MRT table wired to server-side pagination/filtering (`manualPagination`, `manualFiltering`, `rowCount`, `onPaginationChange`, `state`).
- [ ] **Step 2:** filters + row selection → detail + the row action menu + `＋New`. Route + **Names** nav tab.
- [ ] **Step 3: test** (MSW): mock `searchUsages` → rows render; setting the `status` filter re-queries with `status=` and shows the filtered set; `total` drives the pager; clicking a row shows `TaxonDetail` on the right; the row ⋮ menu opens.
- [ ] **Step 4:** `npm run test` + `npm run build` green; commit `feat(frontend): Names search table (filters + shared detail + actions)`.

---

## Self-Review Notes

- **Covers the request:** create a taxon (root/child/synonym via toolbar `＋New` + contextual menu — Task 2); flat search table with rank/status filters (Task 3); both tree + table select into the shared `TaxonDetail` (Tasks 2–3 reuse it); create placement = toolbar + row menu; actions = ⋮ + right-click (Task 2's `NameActionMenu`, reused in Task 3).
- **Deferred (recorded):** **move/reparent** from the UI (backend ready; target-picker later); unlink-synonym from the UI; bulk/multi-select actions; revealing/scrolling the tree to a name selected in the search; server-side sort in the table (start with the default order). "Rename" isn't a separate action — select the row and edit `scientificName` in `TaxonDetail`.
- **Consistency:** rank/status use enum-name wire form (matching `TaxonDetail`/`NameUsageResponse`), NOT the lowercase project nomCode/license form. The action menu + create modal are single shared components used by both views (DRY).
- **Manual verification:** in the Tree, `＋New` (root) and per-row ⋮ → Add child / Add synonym / Change status / Delete; the tree refreshes and selects the new node. In **Names**, filter by rank+status+text, click a row → detail on the right, use the same ⋮ menu.
