# Homotypic grouping — Side 2: cross-dataset consolidation of accepted names

*Design spec — 2026-07-20*
**Status:** Implemented 2026-07-20 (plan: docs/superpowers/plans/2026-07-20-homotypic-consolidation-side2.md).

## Context

Side 1 (shipped) detects homotypic structure *within one accepted taxon's synonymy*, stores it
as `name_relation` rows, and renders a nested synonymy. It left a pure clustering engine
(`HomotypyDetector`, a BasionymSorter-lite over parsed authorship), the `name_relation`
single-source-of-truth model, and a `synonymy` read model.

Side 2 tackles the complementary problem: **within a chosen higher taxon's subtree (typically a
family), homotypic names must resolve to exactly one accepted name.** A homotypic group whose
members resolve to *more than one* distinct accepted name is illegal (one type ⇒ one accepted
taxon). The curator reviews these conflicts and, for each, picks the single survivor; the other
accepted names are demoted to homotypic synonyms of it.

This is the editor analogue of CLB's `life.catalogue.basgroup.HomotypicConsolidator`. From that
code we reuse the *shape* of detection (epithet-bucket → author-cluster → keep groups resolving to
>1 accepted) and the *mutation* (convert a losing accepted name to a synonym, reparenting its
children and re-pointing its synonyms to the survivor). We deliberately do **not** port CLB's
sector/release priority machinery, its `findPrimaryUsage` sector-based survivor pick, its
background-job threading, its `IssueAdder`/`verbatim_source` bookkeeping, or its auto-delete of
duplicate-label losers — the curator supplies the judgment those encode.

## Decisions (settled during brainstorming)

1. **Full page, not a modal.** Result sets can be large (a whole family), so conflicts render on a
   dedicated, paginated page rather than a modal.
2. **Generalized conflict via accepted-target resolution.** Clustering includes **accepted AND
   synonym** usages. A cluster is a conflict when its members resolve to **>1 distinct accepted
   name**, where a member's accepted name is *itself* (if accepted) or its *accepted target(s)*
   (if a synonym). This unifies three shapes: two accepted names homotypic; an accepted + a
   homotypic synonym of a different accepted; two homotypic synonyms of different accepteds.
3. **Pro-parte / dual-status exceptions are flagged, never auto-resolved.** A pro-parte synonym
   (linked to several accepted names) or a name present both as an accepted usage and a separate
   synonym usage legitimately makes a cluster show >1 accepted name. The scan marks these members;
   the curator consolidates anyway or **skips**. Skip is **non-persistent** in v1 (the conflict
   reappears on the next scan; no dismissal state).
4. **Suggested survivor = most descendants.** Default the survivor to the accepted name with the
   largest accepted subtree (fewest taxa move), ties broken by combination-authorship year then
   name. The curator can pick any of the cluster's accepted names.
5. **Re-point synonyms; demote only homotypic accepted names.** The survivor is the accepted name
   that owns the type. Every OTHER accepted name that is itself a homotypic **member** of the
   cluster is demoted to a `SYNONYM` of the survivor (via `demote()`, children + synonyms →
   survivor). Every **synonym member** of the cluster is re-pointed to the survivor (link to
   survivor, unlink from its other accepted targets). An accepted name reached **only** as a
   synonym's target (heterotypically related — e.g. `Festuca foo` when a misplaced synonym under it
   is homotypic to the survivor) is **never** demoted; it simply loses the re-pointed synonym. The
   cluster's homotypic `name_relation`s are then persisted so the survivor's Side-1 synonymy renders
   the homotypic names as `≡`. *(This is why demoting every non-survivor accepted candidate is wrong:
   a synonym-target accepted taxon is not itself homotypic — only its synonym is.)*

## Detection

### Reused / extended primitives

- **`HomotypyDetector` refactor (small):** extract the clustering loop into
  `group(List<NameUsage> candidates, Set<String> existingKeys) → HomotypyProposal` (status-agnostic;
  callers pre-filter). `detect(accepted, synonyms, existingKeys)` becomes a thin wrapper passing
  `[accepted] + synonyms`. Side 2 passes the subtree's usages. No change to the clustering logic
  (epithet-bucket + basionym-or-combination author key); it still designates a basionym per cluster
  and proposes `basionym`/`homotypic` relations.
- `NameUsageMapper.findSubtreeIds(pid, rootId)` (existing) — every id in the accepted classification
  subtree (a `parent_id` walk; synonyms have `parent_id = null` and are **not** returned).
- `SynonymAcceptedMapper.findSynonymsOf(pid, acceptedId)` / `findAcceptedFor(pid, synonymId)`
  (existing) — an accepted usage's synonym ids / a synonym's accepted target ids (empty for
  unlinked; several for pro parte).

### Scan algorithm (`ConsolidationService.scan(userId, pid, rootId)`)

1. `requireRole`; collect candidate ids = every `findSubtreeIds(rootId)` id **unioned with each
   id's `findSynonymsOf`** (synonyms carry `parent_id = null`, so the subtree walk alone misses
   them); load each as `NameUsage`.
2. **Pre-filter** to clusterable usages: keep `ACCEPTED` and `SYNONYM`; drop `MISAPPLIED` and
   `UNASSESSED`; drop supraspecific (blank `specificEpithet`) and autonyms
   (`infraspecificEpithet` equals `specificEpithet`). *(OTU/NameType filtering is unnecessary —
   Blixa's parser doesn't emit OTUs.)*
3. `group(candidates, existingRelationKeys)` → clusters.
4. For each cluster, resolve the **distinct accepted-target set**: an `ACCEPTED` member contributes
   its own id; a `SYNONYM` member contributes each id from `findAcceptedFor`. Keep clusters whose
   set has size **> 1** — these are the conflicts.
5. Build a `ConflictCluster` DTO per conflict (below), including the suggested survivor and the
   pro-parte / dual-status flags. Return `List<ConflictCluster>` (the page paginates client-side; a
   subtree scan is a single call).

Accepted targets may lie **outside** the scanned subtree (a synonym in the family pointing to an
accepted name elsewhere); they are still valid survivor candidates, and `demote()` operates
project-wide, so this is correct.

### Flags

- **pro-parte member:** a `SYNONYM` cluster member with >1 accepted target.
- **dual-status:** the same `scientificName` appears in the cluster both as an `ACCEPTED` usage and
  as a `SYNONYM` usage.

A cluster carries `hasExceptions = true` when any member is pro-parte or dual-status, so the page
can badge it and the curator treats it with extra care.

## Consolidation

### `ConsolidationService.consolidate(userId, pid, ConsolidateRequest)`

`requireEditor`, `@Transactional` (one cluster resolves atomically). Request carries the chosen
`survivorId`, the `losers` (each `{acceptedId, version}` — the accepted **members** of the cluster
other than the survivor, with their optimistic-lock versions), the `repoint` (ids of the cluster's
**synonym members** that must move to the survivor), and the cluster's `relations` (the detector's
proposed homotypic relations).

1. **Demote each loser accepted member** to the survivor:
   `demote(userId, pid, loser.acceptedId, new DemoteRequest(survivorId, "SYNONYM", "new-accepted",
   "new-accepted", loser.version))` — sets the loser to `SYNONYM` of the survivor, reparents its
   accepted children to the survivor, and re-points its own synonyms to the survivor. A stale
   version → 409 (the whole cluster transaction rolls back; the curator re-scans).
2. **Re-point each synonym member** to the survivor: `linkSynonym(userId, pid, synonymId,
   survivorId)` then, for every current accepted target `t ≠ survivorId` of that synonym
   (`findAcceptedFor`), `unlinkSynonym(userId, pid, synonymId, t)`. Link-before-unlink so the
   synonym is never momentarily orphaned; both ops are idempotent, so a synonym a step-1 demote
   already moved is a no-op here.
3. **Persist** the cluster's homotypic `name_relation`s through Side-1's idempotent apply
   (`nameRelations.exists` guard + `insert`), so the survivor's synonymy renders the homotypic
   names as `≡`, and return the survivor's refreshed `Synonymy`.

The frontend computes `losers`/`repoint` from the chosen survivor: `losers` = cluster members with
status `ACCEPTED` and id ≠ survivor; `repoint` = cluster members with status `SYNONYM` not already
pointing solely at the survivor. So which accepted names get demoted depends on which candidate the
curator picks: picking an accepted member `A` demotes the *other* accepted members and re-points the
synonym members to `A` (leaving synonym-target accepteds like `Festuca foo` alone); picking a
synonym-target accepted `F` demotes the accepted members into `F` and leaves `F` accepted.

### Guards / edge behavior

- A demoted loser must currently be `ACCEPTED` and not the survivor (enforced by `demote()`'s own
  checks). Only accepted **members** of the cluster are ever demoted — never an accepted name
  reached solely as a synonym's target.
- Rank safety is automatic: the scan only clusters species/infraspecific usages, so losers are
  never genus-or-above (CLB's explicit rank guard is unnecessary here).
- Duplicate-label losers are **kept as synonyms** (no auto-delete); the curator removes true
  duplicates separately.
- Pro-parte collapse: re-pointing a pro-parte synonym member drops its other accepted links. Because
  this is lossy for a legitimate pro parte, such clusters are flagged (`hasExceptions`) and the
  curator chooses to proceed or skip.

## API

Under the usage controller, package `…name.homotypy`:

- `GET /api/projects/{pid}/usages/{id}/homotypic/conflicts` → `List<ConflictCluster>`. Scans the
  accepted subtree rooted at `{id}`. Any member may read.
- `POST /api/projects/{pid}/usages/{survivorId}/homotypic/consolidate` — body `ConsolidateRequest`
  `{ losers: [{acceptedId, version}], repoint: [synonymId…], relations: [{usageId, relatedUsageId,
  type}] }`. Owner/editor. Returns the survivor's `Synonymy`.

DTOs (`…name.homotypy/dto/`):

- `ConflictCluster(List<AcceptedCandidate> accepted, List<ConflictMember> members,
  Integer suggestedSurvivorId, boolean hasExceptions, List<ProposedRelation> relations)`
- `AcceptedCandidate(int id, String formattedName, int descendantCount)` — the survivor choices
  (all the distinct accepted names the cluster resolves to — accepted members plus synonym targets).
- `ConflictMember(int id, String formattedName, String status, List<Integer> acceptedTargetIds,
  int version, boolean proParte, boolean dualStatus)` — every clustered name, for display; `version`
  is the usage's optimistic-lock version (used when an accepted member is demoted).
- `ConsolidateRequest(List<LoserRef> losers, List<Integer> repoint, List<ApplyRelation> relations)`
  with `LoserRef(int acceptedId, int version)`.

`descendantCount` per candidate comes from the `findSubtreeIds` CTE size (minus self); a subtree
scan is small enough that per-candidate counts are cheap. Suggested survivor = max
`descendantCount`, ties by combination-authorship year (oldest) then formatted name.

## Frontend

A **Homotypic conflicts** page, route `/projects/:projectId/homotypic-conflicts/:rootId`, launched
from a focal taxon (tree context menu / TaxonDetail action "Find homotypic conflicts"). It:

- Fetches `GET …/usages/{rootId}/homotypic/conflicts`; shows a header with the root taxon name and
  the conflict count; paginates the list client-side.
- Renders each `ConflictCluster` as a card: the clustered names grouped (accepted names emphasized,
  synonyms with their accepted target shown), pro-parte / dual-status **badges** when
  `hasExceptions`, and a survivor **radio group** over the accepted candidates (suggested one
  pre-selected, descendant counts shown).
- **Consolidate** posts `POST …/usages/{survivorId}/homotypic/consolidate` with the other accepted
  names as `losers` (+ their versions) and the cluster's `relations`; on success removes the card
  and invalidates the tree / synonymy queries. **Skip** just collapses the card locally
  (non-persistent).
- Empty scan → "No homotypic conflicts found in this subtree."

`api/usages.ts` gains `getHomotypicConflicts(pid, rootId)` and
`consolidateHomotypic(pid, survivorId, {losers, relations})` + the matching types. Reuses the
existing modal/card/React-Query conventions (`Synonymy.tsx`, `HomotypicGroupModal.tsx`).

## Testing (TDD, RED first)

**Backend**

- `HomotypyDetectorTest` (extend): `group(candidates, keys)` clusters a flat mixed-status list the
  same way `detect` does (delegation preserved; existing 6 cases stay green).
- `ConsolidationServiceTest` / `ConsolidationApiIT`:
  - scan finds a cluster of two homotypic **accepted** names as a conflict; a cluster resolving to
    a single accepted name is **not** flagged.
  - the generalized shapes: accepted + homotypic synonym-of-another-accepted; two homotypic
    synonyms of different accepteds — both flagged as conflicts with the right accepted-target set.
  - pro-parte (a synonym with two accepted targets) and dual-status (same name accepted + synonym)
    clusters are flagged with `hasExceptions` / member badges, not silently dropped.
  - suggested survivor = most descendants (ties → year, name).
  - consolidate demotes each loser to a `SYNONYM` of the survivor, reparents children + synonyms to
    the survivor, persists the homotypic relations (survivor's `synonymy` then shows them under
    `homotypic`), and is atomic (a stale loser version → 409, no partial changes).
  - authz: viewer may scan (200), not consolidate (403); non-member 404.

**Frontend**

- `HomotypicConflictsPage.test.tsx`: renders conflict cards from a mocked scan (accepted
  candidates, synonym members, exception badges); selecting a non-default survivor + Consolidate
  posts the correct `losers`/`survivorId`; Skip removes the card; empty state.

## Verification

- Backend TDD per the repo convention (`-Dit.test=ConsolidationApiIT … verify`, RED→GREEN; full
  `./mvnw clean verify` before commit).
- Frontend: `npx vitest run src/…/HomotypicConflictsPage` + `npx tsc -b` + `npm run build`.
- Manual: import a dataset with a known homotypic conflict (e.g. an accepted `Poa annua` and an
  accepted `Ochlopoa annua` in the same family) → open the family → "Find homotypic conflicts" →
  confirm the cluster lists both, suggests the larger, and consolidating sinks the other as a `≡`
  synonym with its children moved under the survivor.
- Commit per logical unit (detector refactor; scan service + API; consolidate + demote reuse;
  frontend page), directly to `main`.

## Out of scope (v1)

- Persistent skip / dismissal state (skips are per-session).
- Routing conflicts into the validation Issues dashboard (open/reviewed lifecycle) — a possible
  later integration.
- Auto-delete of duplicate-label losers; CLB sector/priority survivor selection; background-job
  scanning of the whole dataset (scan is one interactive subtree call).
- Whole-dataset (non-subtree) scanning and family-by-family bucketing/parallelism.
