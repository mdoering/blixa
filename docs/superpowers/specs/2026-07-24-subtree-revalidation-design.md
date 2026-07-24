# Subtree (group) revalidation — design

*2026-07-24*

## Problem

Validation is triggered at only two granularities today:

1. **Per-usage, automatic.** Every write service publishes `ValidationEvent.forUsage(projectId, usageId)`; `ValidationTrigger` revalidates that one usage on an async pool, after the write commits.
2. **Whole-project, on-demand.** `POST /api/projects/{pid}/revalidate` loops every usage in the project.

There is nothing in between. Two consequences:

- On a large project the only way to force a recompute is the whole-dataset hammer.
- The per-usage trigger **misses relational rules**. Editing a genus's year changes whether its child species trips `genus_year_after_species`; renaming a name changes a *sibling's* `duplicate_name`; changing an accepted name's rank flips its *synonyms'* `synonym_rank_differs`. The single-usage trigger never re-checks the neighbours.

When a curator works a **group** — a locked subtree, usually tagged with an objective — they want to sweep just that subtree.

## Scope

Add a third granularity: **revalidate the subtree rooted at a usage.**

Triggered two ways (both chosen by the user):

- **Manual button on the taxon** — an owner/editor action that recomputes the focal taxon's subtree and returns a summary.
- **Automatically on lock release** — when a lock is released (explicit release *or* the retention sweep expiring it), revalidate its subtree.

Not in scope: auto-revalidate on objective *close* (rejected); a subtree-scoped Issues panel filter (the panel already filters by other axes; out of scope here).

## Core: `ValidationService.revalidateSubtree`

```java
public void revalidateSubtree(int projectId, int rootUsageId) {
  for (int usageId : nameUsages.findSubtreeIds(projectId, rootUsageId)) {
    self.revalidateUsage(projectId, usageId);
  }
}
```

Mirrors `revalidateProject` exactly — not `@Transactional` itself, calls through the `self` proxy so each usage is its own transaction (prompt advisory-lock release, no one giant transaction). `findSubtreeIds` already exists and includes the root itself, ordered by the same `depth < 10000` cycle guard as the other CTEs. A deleted/absent root yields an empty list → no-op.

## Manual trigger

`IssueService.revalidateSubtree(actorId, projectId, rootUsageId)`:
- gate `requireRole(owner/editor)` — same tier as `revalidateProject`;
- call `validationService.revalidateSubtree`;
- return an `IssueSummaryResponse` **scoped to the subtree** (errors/warnings/info counts over the subtree's usage ids), so the button can report "N errors, M warnings in this group". Adds `IssueMapper.summarizeEntities(projectId, ids)` (COUNT grouped by severity over `entity_id IN (…)`), or reuses the existing summary query with an id filter.

Endpoint (in `IssueController`, which already owns `/revalidate`):

```
POST /api/projects/{pid}/usages/{id}/revalidate  → IssueSummaryResponse
```

Synchronous (like the project revalidate) so the caller gets the summary back. Owner/editor only.

Frontend: a **"Revalidate subtree"** action in the TaxonDetail actions menu (icon/context-menu per the UI convention, gated on the edit role). On success, a Mantine notification shows the returned counts. TanStack Query invalidates the issues + usage queries so badges refresh.

## Automatic trigger on lock release

A new event, kept separate from `ValidationEvent` so the single-usage listener is untouched:

```java
public record SubtreeValidationEvent(int projectId, int rootUsageId) {}
```

`ValidationTrigger` gains a second listener, same async/exception-swallowing contract:

```java
@Async(ValidationAsyncConfig.EXECUTOR_BEAN)
@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)
public void onSubtreeValidationEvent(SubtreeValidationEvent e) {
  try { validationService.revalidateSubtree(e.projectId(), e.rootUsageId()); }
  catch (Exception ex) { log.warn(...); }
}
```

`fallbackExecution = true` is the load-bearing detail: the **release** path publishes from inside a transaction (fires AFTER_COMMIT), but the **sweep** path has no transaction — `fallbackExecution` makes the listener run immediately (still on the async pool) instead of being silently dropped. One listener serves both.

**Gate: only locks that carry an objective (`discussionId != null`) trigger a subtree revalidate.** This matches the user's framing ("when working on a *group*") and bounds the cost — ad-hoc ungrouped locks (open an editor, tweak one field, move on) don't kick off a subtree sweep; only deliberate grouped work does. It also keeps the single-usage auto-trigger as the sole mechanism for casual edits.

### Release path (`LockService.release`)

`release` currently deletes by `lockId` without loading the row. Change to load it first (`locks.findById`) to learn `entityType` / `entityId` / `discussionId`, delete, then — only if the delete succeeded, `entityType == name_usage`, and `discussionId != null` — publish `SubtreeValidationEvent(projectId, entityId)`. Inject `ApplicationEventPublisher`.

### Sweep path (`LockRetentionSweep.sweep`)

The bulk `deleteExpired()` doesn't know which entities it removed. Add `LockMapper.findExpiredObjectiveUsageRoots()` — `SELECT entity_id FROM lock WHERE entity_type = 'name_usage' AND discussion_id IS NOT NULL AND expires_at <= now()` — fetched *before* `deleteExpired()`, then publish one `SubtreeValidationEvent` per root. Inject `ApplicationEventPublisher` into the sweep. (Small race window: a lock could be refreshed between the SELECT and the delete; worst case a still-held lock's subtree gets a harmless extra revalidate. Acceptable — revalidation is idempotent.)

## Idempotency / safety

`revalidateUsage` is already idempotent (byte-identical issue set on a re-run of unchanged data — `ValidationReconcileIT`), so overlapping triggers (manual + release + sweep) only ever converge. Each usage's `IssueMapper.lockUsage` advisory lock serializes concurrent revalidations of the same usage. No new locking needed.

## Testing

- **Unit** — `revalidateSubtree` delegates to `findSubtreeIds` + per-usage revalidate (verify the loop; mock the mapper).
- **IT** (`SubtreeRevalidationIT`, real Postgres):
  - Manual endpoint recomputes a subtree and returns correct counts; a sibling subtree is untouched.
  - Relational rule: create a genus + species where editing the genus's year *should* flip the species' `genus_year_after_species`; assert the per-usage trigger alone leaves the species stale, and the subtree revalidate fixes it.
  - Release of an **objective-tagged** `name_usage` lock revalidates its subtree; release of an **untagged** lock does not.
  - The sweep revalidates expired objective-tagged lock subtrees (drive `sweep()` directly after inserting an already-expired lock).
- **Frontend** — TaxonDetail shows the action for editors, hidden for viewers; clicking calls the endpoint and surfaces the summary notification (MSW handler).

## Rollout

Backend first (service + event + endpoint + lock wiring + ITs), then the frontend button. One commit per green layer, on `main`.
