# P2 — Accepted↔synonym workflow (demote / promote)

**Date:** 2026-07-09
**Scope:** Atomic backend `demote`/`promote` orchestration + guided frontend modals off the ⋮
"Change status". Builds on P1 (taxon_info). Part of the synonym-management effort (P1 done; P3/P4 follow).

## Rules (from `features.md`, settled in brainstorming)

- Only accepted names sit in the tree and carry taxon info; synonyms point to ≥1 accepted; no chaining.
- **acc→syn (demote):** pick a new accepted target; if the node has accepted children, **ask** where
  they go (under the new accepted, or up to the node's former parent); if the node has its own
  synonyms, **ask** whether to re-point them to the new accepted or set them **unassessed**. Taxon
  info is shed (a synonym can't carry it — automatic via P1's accepted-only invariant; no migration
  this round). misapplied is treated like synonym.
- **syn→acc (promote):** place it in the tree (a chosen accepted parent, or root); drop all its
  synonym links. Pro-parte "keep some relations" is **deferred to P3** (needs the split).
- syn↔misapplied stays a plain status update; acc→unassessed is **not** in the guided flow this round.

## Backend

### `POST /api/projects/{pid}/usages/{id}/demote`

Body `DemoteRequest { Integer acceptedId, String status, String childrenTo, String synonymsTo, int version }`.
Returns the updated node's `NameUsageResponse` (200). Owner/editor only.

One `@Transactional` (with `tree.lockProject(pid)` first, like `move`):
1. `node = requireInProject(pid, id)`; must be `ACCEPTED` (else 400 "only accepted usages can be demoted").
2. Parse `status` → `Status.SYNONYM` or `MISAPPLIED` (else 400).
3. `acceptedId` required; `target = requireInProject(pid, acceptedId)` must be `ACCEPTED`, `!= id`, and
   **not a descendant** of `id` (`tree.isDescendant(pid, id, acceptedId)` → 400 "would create a cycle").
4. Snapshot `before` (node → Map). CAS-update the node: `status`, `parent_id = null`, via
   `usages.update(node)` with `node.setVersion(req.version())` → 0 rows ⇒ 409. Then `writeTaxonInfo(node)`
   (drops its taxon_info, now non-accepted).
5. Children (`childIds = usages.findChildIds(pid, id)`, i.e. `parent_id = id`): if non-empty, require
   `childrenTo ∈ {new-accepted, former-parent}` (else 400); `newParent = new-accepted ? acceptedId :
   node.parentId(before)`; `usages.reparentChildren(pid, id, newParent, actorId)` (bulk, version-bumping).
6. Node's own synonyms (`synIds = synonymAccepted.findSynonymsOf(pid, id)`): if non-empty, require
   `synonymsTo ∈ {new-accepted, unassessed}` (else 400):
   - **new-accepted:** for each `s`: `link(pid, s, acceptedId, null)` (ON CONFLICT DO NOTHING) then
     `unlink(pid, s, id)`.
   - **unassessed:** for each `s`: `unlink(pid, s, id)`; if `countBySynonym(pid, s) == 0` then
     `setStatus(pid, s, UNASSESSED, actorId)` (a pro-parte synonym still linked elsewhere keeps its status).
7. `link(pid, id, acceptedId, null)` (node becomes a synonym of target).
8. `audit.record(... UPDATE, before, after)`; publish `ValidationEvent.forUsage` for the node, each moved
   child, and each affected synonym.

**Note:** the node's own CAS-update runs before the child/synonym reshuffle so a stale version fails
fast (whole tx rolls back). The child/synonym bulk writes are server-orchestrated (no per-row client
version) under the project advisory lock.

### `POST /api/projects/{pid}/usages/{id}/promote`

Body `PromoteRequest { Integer parentId, int version }`. Returns the updated node's `NameUsageResponse`.
Owner/editor only. One `@Transactional` (+ `lockProject`):
1. `node = requireInProject(pid, id)`; must be `SYNONYM` or `MISAPPLIED` (else 400 "only synonyms can be promoted").
2. If `parentId != null`: `parent = requireInProject`, must be `ACCEPTED` (else 400).
3. Snapshot `before`. CAS-update: `status = ACCEPTED`, `parent_id = parentId` → 0 rows ⇒ 409.
4. `synonymAccepted.deleteBySynonym(pid, id)` (drop all its links).
5. `audit.record(...)`; publish `ValidationEvent.forUsage(pid, id)`.

### New mapper methods

- `NameUsageMapper.findChildIds(pid, parentId) : List<Integer>` — `SELECT id ... WHERE parent_id = ?`.
- `NameUsageMapper.reparentChildren(pid, oldParentId, newParentId, modifiedBy) : int` — bulk
  `UPDATE ... SET parent_id=?, version=version+1, modified=now(), modified_by=? WHERE parent_id=?`.
- `NameUsageMapper.setStatus(pid, id, status, modifiedBy) : int` — `UPDATE ... SET status=?, version=version+1, ...`.
- `SynonymAcceptedMapper.deleteBySynonym(pid, synonymId) : int` — `DELETE ... WHERE synonym_id=?`.

### DTOs / controller

- `name/dto/DemoteRequest.java`, `name/dto/PromoteRequest.java` (records).
- `NameUsageController`: `@PostMapping("/{id}/demote")`, `@PostMapping("/{id}/promote")` → 200 with the response body.

## Frontend

- **`api/usages.ts`**: `demoteUsage(pid, id, {acceptedId, status, childrenTo?, synonymsTo?, version})` and
  `promoteUsage(pid, id, {parentId, version})` (POST, return `NameUsage`).
- **`useNameActions.changeStatus`** becomes status-aware: ACCEPTED→(SYNONYM|MISAPPLIED) opens a
  **DemoteModal**; (SYNONYM|MISAPPLIED)→ACCEPTED opens a **PromoteModal**; everything else keeps the
  existing naive full-update path. `NameActionMenu` renders the two modals off new hook state
  (`demoteTarget`/`promoteTarget`), mirroring `moveTarget`.
- **`DemoteModal`** (new): loads `getUsage` (version + `synonymIds`) + `getChildren` (child count).
  Target picker = `ClassificationTree` with `disabledId = node.id` (self+subtree greyed, reused from
  Move). SegmentedControl Synonym/Misapplied. Radios shown **only when applicable**: children
  (`Under the new accepted` / `Up to the former parent`, with the child count), synonyms
  (`Re-point to the new accepted` / `Set unassessed`, with the synonym count). Demote button disabled
  until a target is picked; on success invalidate treeRoots/children/usage/treePath/usageSearch +
  synonym lists; a 409 → notify+refresh; a 400 → inline error.
- **`PromoteModal`** (new): like `MoveNameModal` — pick a parent (tree picker) or "Make it a root";
  Promote → same invalidations.

## Testing

- Backend `NameUsageApiIT` (or a new `AccSynWorkflowIT`): demote a leaf accepted → becomes SYNONYM
  linked to target, parent null; demote a genus with children choosing new-accepted → children reparented
  to target; demote a node whose synonyms → unassessed (orphaned) vs re-pointed; 400s (non-accepted node,
  descendant target, missing childrenTo/synonymsTo); 409 stale version. Promote a synonym → ACCEPTED under
  a chosen parent, links gone; 400 promoting an accepted; 409 stale.
- Frontend: `demoteUsage`/`promoteUsage` client tests; DemoteModal (picker disables self, conditional
  radios appear with counts, POSTs the right body); PromoteModal (parent vs root). Existing tests stay green.

## Verification

`mvn verify` + `npm test` + build green. Browser: on the seeded Felidae tree, demote a species to a
synonym of another species and confirm it leaves the tree + appears under the target's synonyms; promote
it back. (Folds into the P2 verification pass.)

## Out of scope (P3/P4)

Interactive unlink / "Add accepted name…" / pro-parte split-on-promote (P3); `genus_mismatch` rule (P4).
