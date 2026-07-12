# Soft-lock Activation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Activate the built-but-dormant soft-lock API in the UI — auto-lock while editing a name, surface active locks in the tree/Names table + a "Current work" list, warn (non-blocking) when a name is held by someone else — plus close backend gaps (expired-lock sweep, configurable TTL, release-on-delete/merge).

**Architecture:** No schema migration (the `lock` table already has everything). Backend adds a `@Scheduled` sweep, a config-driven TTL, and two `LockMapper` methods (`deleteExpired`, `deleteByEntity`) wired into delete/merge. Frontend builds the first-ever lock UI: `api/locks.ts`, a `useUsageLock` lifecycle hook, a shared `['locks', pid]` query feeding row indicators + a detail banner + a Current-work page. Locks stay **advisory** — the optimistic `version` CAS is the only enforced write guard.

**Tech Stack:** Backend Spring Boot 4.1 / Java 25 / Postgres 17 / MyBatis / Testcontainers ITs. Frontend React + TS + Mantine 7 + TanStack Query + react-router + Vitest + MSW.

## Global Constraints

- Locks are **advisory only** — never gate/block any write on a lock; `version` CAS stays the enforcement. Never add a lock *check* before a mutation.
- Any project **member** may lock (owner/editor/viewer via `requireRole`), but the **frontend only auto-locks for editors** (`canEdit`); viewers never acquire.
- Reuse existing endpoints unchanged: `POST /api/projects/{pid}/locks` (200 if `heldByMe`, else **409** with the real holder in the body — treat 409 as a *soft* result, not an error), `GET /locks`, `POST /locks/{id}/refresh?ttlSeconds=`, `DELETE /locks/{id}`.
- `entity_type` stays `"name_usage"` this phase.
- Build with JDK 25: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw ...`. Run `clean test-compile` if any record arity changes.
- Frontend gates: `cd frontend && npx tsc -b && npx vitest run`.
- Commit directly to `main`. **DO NOT stage** `todo.md`, `blixa.svg`, `backend/src/main/resources/application-dev.yml`. Do NOT touch the Jenkinsfile.
- Wire forms: name-usage `status` UPPERCASE, `rank` lowercase (unchanged; only relevant if a task touches usage rows).

---

### Task 1: Backend — sweep, configurable TTL, release-on-disappear

**Files:**
- Modify: `backend/src/main/java/org/catalogueoflife/editor/lock/LockMapper.java` (add `deleteExpired`, `deleteByEntity`)
- Create: `backend/src/main/java/org/catalogueoflife/editor/lock/LockRetentionSweep.java`
- Modify: `backend/src/main/java/org/catalogueoflife/editor/lock/LockService.java` (config-driven TTL)
- Modify: `backend/src/main/java/org/catalogueoflife/editor/name/NameUsageService.java` (release lock on delete)
- Modify: `backend/src/main/java/org/catalogueoflife/editor/mergerecords/MergeRecordsService.java` (release lock on merge)
- Test: `backend/src/test/java/org/catalogueoflife/editor/lock/LockSweepIT.java` (new); extend `backend/src/test/java/org/catalogueoflife/editor/mergerecords/UsageMergeApplyIT.java`

**Interfaces:**
- Consumes: existing `LockMapper` (`upsertTakeover/findByEntity/findActive/findById/refresh/delete`, all `@Param`-annotated); `LockService` constants `DEFAULT_TTL_SECONDS=300 / MIN=30 / MAX=3600`; `NameUsageService.delete(int userId, int projectId, int id)` (cleans up at the `issues.deleteByEntity(projectId, ENTITY, id)` line, `ENTITY="name_usage"`); `MergeRecordsService.mergeUsages` loop (per merged `d`, has `issues.deleteByEntity(projectId, "name_usage", d)` before `usages.delete`).
- Produces: `LockMapper.deleteExpired()` and `LockMapper.deleteByEntity(int projectId, String entityType, int entityId)`; `LockRetentionSweep` bean; config keys `coldp.lock.ttl-seconds` (default 300), `coldp.lock.sweep` (default `PT5M`).

- [ ] **Step 1: Write the failing sweep IT**

`LockSweepIT.java` (mirror an existing `AbstractPostgresIT` mapper IT; autowire `LockMapper` + a way to seed rows — use `upsertTakeover` then hand-expire, or insert directly via the mapper). Seed one **expired** lock (`expires_at <= now()`) and one **live** lock, call `lockMapper.deleteExpired()`, assert only the live one remains in `findActive(projectId)` and the expired row is physically gone.

```java
@Test
void deleteExpiredRemovesOnlyExpiredRows() {
  // seed a live lock (ttl 300) and an expired one (ttl negative / past expires_at)
  // ... acquire live via LockService or upsertTakeover(ttl=300); for expired, upsertTakeover then
  //     UPDATE its expires_at into the past via a tiny @Update, OR insert with ttl small + sleep is
  //     disallowed -> use a direct past-timestamp insert helper.
  int removed = lockMapper.deleteExpired();
  assertThat(removed).isEqualTo(1);
  assertThat(lockMapper.findActive(projectId)).extracting(Lock::getEntityId).containsExactly(liveEntityId);
}
```
(If seeding an expired row is awkward through `upsertTakeover` — its `ttl` is `int` seconds added to `now()` — add a package-private test-only `@Update("UPDATE lock SET expires_at = now() - interval '1 hour' WHERE id = #{id}")` on the mapper, or insert via a raw JdbcTemplate in the test. Prefer the mapper approach so it's one file.)

- [ ] **Step 2: Run it to verify it fails** — `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=LockSweepIT test` → FAIL (`deleteExpired` undefined).

- [ ] **Step 3: Add the two mapper methods** to `LockMapper.java`:

```java
  @Delete("DELETE FROM lock WHERE expires_at <= now()")
  int deleteExpired();

  @Delete("DELETE FROM lock WHERE project_id = #{projectId} AND entity_type = #{entityType} AND entity_id = #{entityId}")
  int deleteByEntity(@Param("projectId") int projectId, @Param("entityType") String entityType,
      @Param("entityId") int entityId);
```

- [ ] **Step 4: Add `LockRetentionSweep`** (mirror `ExportRetentionSweep`'s structure — `@Component`, constructor-inject `LockMapper`, a `@Scheduled(fixedDelayString = "${coldp.lock.sweep:PT5M}")` method logging the deleted count):

```java
@Component
public class LockRetentionSweep {
  private static final Logger log = LoggerFactory.getLogger(LockRetentionSweep.class);
  private final LockMapper locks;
  public LockRetentionSweep(LockMapper locks) { this.locks = locks; }

  @Scheduled(fixedDelayString = "${coldp.lock.sweep:PT5M}")
  public void sweep() {
    int n = locks.deleteExpired();
    if (n > 0) log.info("Swept {} expired lock(s)", n);
  }
}
```
(`@EnableScheduling` is already app-wide via `ExportAsyncConfig` — do NOT re-add it.)

- [ ] **Step 5: Make TTL configurable** in `LockService.java` — replace the `static final int DEFAULT_TTL_SECONDS = 300;` usage with an injected field, keeping MIN/MAX clamp:

```java
  private final int defaultTtlSeconds;
  public LockService(/* existing deps */, @Value("${coldp.lock.ttl-seconds:300}") int defaultTtlSeconds) {
    // ... existing assignments
    this.defaultTtlSeconds = defaultTtlSeconds;
  }
```
Update the clamp helper to use `defaultTtlSeconds` instead of the constant when `ttlSeconds == null`. Keep `MIN_TTL_SECONDS`/`MAX_TTL_SECONDS` as constants. (If the constructor is currently default/implicit, add one; adjust the existing `LockApiIT` only if it constructs `LockService` directly — it does not, it goes through MockMvc, so no test change needed.)

- [ ] **Step 6: Release the lock when the usage is deleted** — in `NameUsageService.java`, inject `LockMapper locks` (constructor) and add, right after `issues.deleteByEntity(projectId, ENTITY, id);`:

```java
    locks.deleteByEntity(projectId, ENTITY, id); // advisory lock is meaningless once the usage is gone
```

- [ ] **Step 7: Release the lock when the usage is merged away** — in `MergeRecordsService.java`, inject `LockMapper locks` and add, inside the `for (int d : mergedIds)` loop right after `issues.deleteByEntity(projectId, "name_usage", d);`:

```java
      locks.deleteByEntity(projectId, "name_usage", d); // release any advisory lock on the merged duplicate
```

- [ ] **Step 8: Add a merge-release assertion** to `UsageMergeApplyIT.java` — in the main happy-path test (or a new focused test), before merging, acquire a lock on the duplicate `d` (`POST /api/projects/{pid}/locks` with `{entityType:"name_usage", entityId:d}` as the same user), merge, then assert `GET /api/projects/{pid}/locks` no longer lists a lock with `entityId == d`.

- [ ] **Step 9: Run the ITs** — `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q -Dtest=LockSweepIT,UsageMergeApplyIT,LockApiIT test` → PASS. Then a clean compile if any constructor arity changed: `./mvnw -q clean test-compile`.

- [ ] **Step 10: Commit** — `git commit -m "feat(lock): expired-lock sweep, configurable TTL, release on usage delete/merge"`

---

### Task 2: Frontend — lock API client + `useUsageLock` lifecycle hook

**Files:**
- Create: `frontend/src/api/locks.ts`
- Create: `frontend/src/lock/useUsageLock.ts`
- Create: `frontend/src/lock/useUsageLock.test.tsx`
- Reference: `frontend/src/api/client.ts` (`api<T>(path, {method, json})`), `frontend/src/api/types.ts` (add `Lock` type)

**Interfaces:**
- Consumes: `api<T>(path, opts)` — `POST` returns parsed JSON; a 409 currently throws in the client, so `acquireLock` must catch the 409 and read the holder from the error/body (see Step 3 note).
- Produces: `listLocks(pid): Promise<Lock[]>`, `acquireLock(pid, {entityType, entityId, ttlSeconds?}): Promise<{lock: Lock; conflict: boolean}>`, `refreshLock(pid, lockId, ttlSeconds?): Promise<Lock>`, `releaseLock(pid, lockId): Promise<void>`; `Lock` type; `useUsageLock(pid, usageId, enabled) → { mine: Lock | null; holder: Lock | null; claim: () => void }`.

- [ ] **Step 1: Add the `Lock` type** to `frontend/src/api/types.ts`:

```ts
export interface Lock {
  id: number;
  entityType: string;
  entityId: number;
  userId: number;
  username: string;
  acquiredAt: string;
  expiresAt: string;
  heldByMe: boolean;
  taskId: number | null;
  taskTitle: string | null;
}
```

- [ ] **Step 2: Write the failing hook test** `useUsageLock.test.tsx` (use `@testing-library/react`'s `renderHook` + `vi.useFakeTimers`, spy `api/locks`):

```tsx
// claim() acquires; heartbeat refreshes; unmount releases.
it('acquires on claim, refreshes on heartbeat, releases on unmount', async () => {
  const acquire = vi.spyOn(locks, 'acquireLock').mockResolvedValue({ lock: fakeLock, conflict: false });
  const refresh = vi.spyOn(locks, 'refreshLock').mockResolvedValue(fakeLock);
  const release = vi.spyOn(locks, 'releaseLock').mockResolvedValue();
  const { result, unmount } = renderHook(() => useUsageLock(1, 10, true), { wrapper });
  act(() => result.current.claim());
  await waitFor(() => expect(acquire).toHaveBeenCalledWith(1, { entityType: 'name_usage', entityId: 10 }));
  act(() => vi.advanceTimersByTime(HEARTBEAT_MS));
  await waitFor(() => expect(refresh).toHaveBeenCalled());
  unmount();
  await waitFor(() => expect(release).toHaveBeenCalledWith(1, fakeLock.id));
});
```

- [ ] **Step 3: Run it to verify it fails** — `cd frontend && npx vitest run src/lock/useUsageLock.test.tsx` → FAIL.

- [ ] **Step 4: Implement `api/locks.ts`**:

```ts
import { api } from './client';
import type { Lock } from './types';

export const listLocks = (pid: number) => api<Lock[]>(`/api/projects/${pid}/locks`);

export async function acquireLock(
  pid: number,
  body: { entityType: string; entityId: number; ttlSeconds?: number },
): Promise<{ lock: Lock; conflict: boolean }> {
  try {
    const lock = await api<Lock>(`/api/projects/${pid}/locks`, { method: 'POST', json: body });
    return { lock, conflict: false };
  } catch (e) {
    // 409 = held by someone else; the body still describes the real holder. Read it if the client
    // exposes the response; otherwise re-fetch listLocks and find the entity. (See client.ts error shape.)
    const holder = (await listLocks(pid)).find(
      (l) => l.entityType === body.entityType && l.entityId === body.entityId,
    );
    if (holder) return { lock: holder, conflict: true };
    throw e;
  }
}

export const refreshLock = (pid: number, lockId: number, ttlSeconds?: number) =>
  api<Lock>(`/api/projects/${pid}/locks/${lockId}/refresh${ttlSeconds ? `?ttlSeconds=${ttlSeconds}` : ''}`, {
    method: 'POST',
  });

export const releaseLock = (pid: number, lockId: number) =>
  api<void>(`/api/projects/${pid}/locks/${lockId}`, { method: 'DELETE' });
```
(First read `client.ts` to see how it surfaces a non-2xx — if it attaches the parsed body/status to the thrown error, prefer reading the holder from there over the extra `listLocks` round-trip. Keep the fallback.)

- [ ] **Step 5: Implement `useUsageLock.ts`**:

```ts
import { useEffect, useRef, useState } from 'react';
import { acquireLock, refreshLock, releaseLock } from '../api/locks';
import type { Lock } from '../api/types';

export const HEARTBEAT_MS = 120_000; // < the 300s server TTL

export function useUsageLock(pid: number, usageId: number | null, enabled: boolean) {
  const [mine, setMine] = useState<Lock | null>(null);
  const [holder, setHolder] = useState<Lock | null>(null);
  const heldRef = useRef<Lock | null>(null);

  // release + reset whenever the target usage changes, the hook disables, or on unmount
  useEffect(() => {
    return () => {
      const h = heldRef.current;
      if (h?.heldByMe) releaseLock(pid, h.id).catch(() => {});
      heldRef.current = null;
      setMine(null);
      setHolder(null);
    };
  }, [pid, usageId, enabled]);

  // heartbeat while we hold our own lock
  useEffect(() => {
    if (!mine) return;
    const t = setInterval(() => {
      refreshLock(pid, mine.id).then((l) => { heldRef.current = l; setMine(l); }).catch(() => {});
    }, HEARTBEAT_MS);
    return () => clearInterval(t);
  }, [pid, mine]);

  function claim() {
    if (!enabled || usageId == null || heldRef.current) return;
    acquireLock(pid, { entityType: 'name_usage', entityId: usageId }).then(({ lock, conflict }) => {
      heldRef.current = lock;
      if (conflict) setHolder(lock);
      else setMine(lock);
    }).catch(() => {});
  }

  return { mine, holder, claim };
}
```

- [ ] **Step 6: Run test + typecheck** — `cd frontend && npx vitest run src/lock/useUsageLock.test.tsx && npx tsc -b` → PASS + clean.

- [ ] **Step 7: Commit** — `git commit -m "feat(lock): frontend lock API client + useUsageLock lifecycle hook"`

---

### Task 3: Frontend — claim-on-edit + "locked by X" banner in TaxonDetail

**Files:**
- Modify: `frontend/src/tree/TaxonDetail.tsx`
- Modify/Create test: `frontend/src/tree/TaxonDetail.test.tsx` (add lock cases; create if absent)
- Reference: the shared locks query key `['locks', pid]` (introduced fully in Task 4; here TaxonDetail may read it via `useUsageLock`'s `holder` OR a direct `listLocks` — prefer `useUsageLock` for the banner to avoid a second source of truth).

**Interfaces:**
- Consumes: `useUsageLock(pid, usageId, canEdit)` from Task 2 (`{ mine, holder, claim }`). `TaxonDetail` already knows `pid`, the `usageId`, and whether the current user can edit (derive `canEdit` the same way the surrounding pages do — read the file).
- Produces: no new exports; behavior only.

- [ ] **Step 1: Read `TaxonDetail.tsx`** to find (a) how it derives edit-permission, (b) its form dirty/change mechanism (onChange handlers / a save action), (c) where a banner can render (top of the panel).

- [ ] **Step 2: Write the failing test** — in `TaxonDetail.test.tsx`, with `api/locks.listLocks`/`acquireLock` stubbed so the open usage is reported **locked by another user**, assert a banner containing the holder's username + "editing" renders; and with no lock (or `heldByMe`), assert it does NOT render. (Mirror the file's existing MSW/render helpers; if the component reads the holder via `useUsageLock`, stub `acquireLock` to resolve `{lock, conflict:true}` on claim, or stub `listLocks`.)

```tsx
it('shows a non-blocking "locked by X" banner when another user holds the lock', async () => {
  vi.spyOn(locks, 'listLocks').mockResolvedValue([
    { ...fakeLock, entityId: usageId, heldByMe: false, username: 'alice' },
  ]);
  render(<TaxonDetail pid={1} usageId={usageId} />, { wrapper });
  expect(await screen.findByText(/alice is editing/i)).toBeInTheDocument();
});
```

- [ ] **Step 3: Run it to verify it fails** — `npx vitest run src/tree/TaxonDetail.test.tsx` → FAIL.

- [ ] **Step 4: Wire the hook into TaxonDetail** — call `const { holder, claim } = useUsageLock(pid, usageId, canEdit);`. Call `claim()` on the first edit interaction (attach to the form's `onChange`/dirty transition and to edit actions like the status change — whatever the file uses; a single `claim()` on first change is enough since it's idempotent). Render, near the top of the panel, when `holder && !holder.heldByMe`:

```tsx
{holder && !holder.heldByMe && (
  <Alert color="orange" variant="light" mb="sm" icon={<IconLock size={16} />}>
    {holder.username} is editing this name — your changes may conflict.
  </Alert>
)}
```
(For the banner to appear even before the current user edits, TaxonDetail also needs to *know* about an existing foreign lock without having claimed. Simplest: additionally read the shared `['locks', pid]` list — see Task 4 — and compute `holder` = the active lock for this `usageId` not held by me. If Task 4 isn't merged yet, do a local `useQuery(['locks', pid], () => listLocks(pid))` here and Task 4 will consolidate. Keep the banner logic reading from that list so it shows a foreign lock immediately, not only after a claim conflict.)

- [ ] **Step 5: Run test + typecheck + full suite** — `npx vitest run src/tree/TaxonDetail.test.tsx && npx tsc -b && npx vitest run` → PASS + clean.

- [ ] **Step 6: Commit** — `git commit -m "feat(lock): claim on edit + non-blocking 'locked by X' banner in TaxonDetail"`

---

### Task 4: Frontend — lock indicators in the tree + Names table

**Files:**
- Modify: `frontend/src/tree/TreeNodeRow.tsx`
- Modify: `frontend/src/names/NameSearchPage.tsx`
- Modify: the tree container that renders `TreeNodeRow` (to fetch + pass the locks map) — find it (`frontend/src/tree/`)
- Test: extend `frontend/src/tree/*TreeNodeRow*.test.tsx` (or the tree page test) and `frontend/src/names/NameSearchPage.test.tsx`

**Interfaces:**
- Consumes: `listLocks(pid)` (Task 2). A shared TanStack query `useQuery({ queryKey: ['locks', pid], queryFn: () => listLocks(pid), refetchInterval: 20_000 })` — build the map `Record<number, Lock>` keyed by `entityId` (name_usage locks only) once per consumer.
- Produces: a small reusable `LockBadge` (optional) or inline icon; a `lock?: Lock` prop on `TreeNodeRow`.

- [ ] **Step 1: Write failing tests** — (a) `TreeNodeRow` renders a lock icon with a tooltip "{username} is editing" when passed a `lock` prop, and none when not; (b) `NameSearchPage`, with `listLocks` stubbed to report a lock on a visible row's usage id, shows the indicator in that row.

- [ ] **Step 2: Run them to verify they fail.**

- [ ] **Step 3: Add the indicator to `TreeNodeRow.tsx`** — accept a `lock?: Lock` prop; render, next to the rank `Badge` (always-visible area, not the hover-only box):

```tsx
{lock && (
  <Tooltip label={`${lock.username} is editing`} withArrow>
    <ThemeIcon size="xs" variant="light" color={lock.heldByMe ? 'gray' : 'orange'}>
      <IconLock size={12} />
    </ThemeIcon>
  </Tooltip>
)}
```

- [ ] **Step 4: Fetch + thread locks in the tree container** — in the component that renders `TreeNodeRow`s, add the shared `['locks', pid]` query, build `locksByEntity`, and pass `lock={locksByEntity[node.id]}` to each row.

- [ ] **Step 5: Add the indicator to `NameSearchPage.tsx`** — add the same `['locks', pid]` query, build the map, and render the icon in the scientific-name cell when `locksByEntity[row.original.id]` exists (mirror the tree's markup).

- [ ] **Step 6: Run tests + typecheck + full suite** → PASS + clean.

- [ ] **Step 7: Commit** — `git commit -m "feat(lock): lock indicators in the classification tree + Names table"`

---

### Task 5: Frontend — "Current work" route, page, and sidebar item

**Files:**
- Create: `frontend/src/lock/CurrentWorkPage.tsx`
- Create: `frontend/src/lock/CurrentWorkPage.test.tsx`
- Modify: `frontend/src/App.tsx` (add the nested route `activity`)
- Modify: `frontend/src/components/AppSidebar.tsx` (add the sidebar item)

**Interfaces:**
- Consumes: `listLocks(pid)` + `releaseLock(pid, lockId)` (Task 2); `useMe` for `heldByMe` fallback; the Names deep-link `?usage=<id>` pattern for click-through (see `NameSearchPage`'s `usageParam`).
- Produces: `CurrentWorkPage` component; route `/projects/:projectId/activity`; sidebar nav item labelled **Activity**.

- [ ] **Step 1: Write the failing page test** — with `listLocks` stubbed to return two active locks (one `heldByMe`), assert both render (name/entity, holder, "expires"); the empty case renders "No one is editing anything right now."; and clicking **Release** on the held row calls `releaseLock` and refetches.

- [ ] **Step 2: Run it to verify it fails.**

- [ ] **Step 3: Implement `CurrentWorkPage.tsx`** — `const pid = Number(useParams().projectId)`; `useQuery(['locks', pid], () => listLocks(pid), { refetchInterval: 20_000 })`; a Mantine `<Table>` of the active locks sorted by `acquiredAt` desc, columns: entity (link to `../names?usage=${entityId}`), holder (`username`, "(you)" when `heldByMe`), acquired ("since"), expires; a **Release** button on `heldByMe` rows calling `releaseLock` then `queryClient.invalidateQueries({ queryKey: ['locks', pid] })`. Empty state text. (Locks are name_usage-only this phase, so "entity" = the name; if the lock list doesn't carry the scientific name, show `name_usage #${entityId}` linking to the Names deep-link — do NOT add a backend join.)

- [ ] **Step 4: Add the route** in `App.tsx` — a nested `<Route path="activity" element={<CurrentWorkPage />} />` under the same `ProjectLayout` that holds tree/names/issues/history.

- [ ] **Step 5: Add the sidebar item** in `AppSidebar.tsx` — append to the per-project `sections` array `{ to: 'activity', label: 'Activity', icon: IconUsers }` (or `IconLock`), same pattern as the others.

- [ ] **Step 6: Run test + typecheck + full suite** → PASS + clean.

- [ ] **Step 7: Commit** — `git commit -m "feat(lock): Current-work (Activity) page listing active locks per project"`

---

## Final verification (after all tasks)

- [ ] Backend full suite: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q test` → green.
- [ ] Frontend gates: `cd frontend && npx tsc -b && npx vitest run` → clean + green.
- [ ] Manual smoke (`docker compose up`): as two users (e.g. two browsers), edit a name as user A → user B sees the lock badge in the tree/Names + the banner on that name + the row in Activity; A closes → lock auto-releases within a heartbeat/TTL; delete/merge a locked name → its lock disappears.

## Notes carried from the spec

- Locks stay **advisory**; `version` CAS is the only enforced guard. Never block a write on a lock.
- The **auto-lock-on-edit** trigger (vs. explicit claim, vs. lock-on-view) is the one product decision the owner should confirm on review (spec §Decisions).
- Deferred (future): subtree locking (ltree), a Tasks UI (`X-Task-Id` grouping), locking references/child entities, owner force-release.
