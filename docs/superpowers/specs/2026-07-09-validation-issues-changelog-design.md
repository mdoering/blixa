# Validation rules + Issues dashboard + Changelog view

**Date:** 2026-07-09
**Scope:** (A) four new backend validation rules; (B) a project-level Issues dashboard; (C) a
project-level History (changelog) view. Backend issue/change APIs already exist — B and C are
frontend over them.

## A. New validation rules (backend)

Each is an `@Component implements ValidationRule` (like `GenusMismatchRule`). `RuleContext` gains
fields, with **convenience constructors** so the 17 base + 2 genus `RuleTests` contexts stay
unchanged; `ValidationService.buildContext` populates the new fields via new `NameUsageMapper`
queries. `Rank` comparison uses `org.gbif.nameparser.api.Rank` (parse the stored lowercase rank via
`Rank.valueOf(rank.toUpperCase())` in a try/catch; unparsable → rule no-ops).

- **`rank_vs_parent`** (WARNING): usage has a `parentRank`, both ranks parse to `Rank`, and the
  usage's rank is **not** strictly below the parent's (`!parent.higherThan(child)`). Skips
  uncomparable ranks.
- **`species_epithet_mismatch`** (WARNING): ACCEPTED usage with a parsed `specificEpithet` and a
  nearest accepted species-rank ancestor whose `specificEpithet` differs (case-insensitive). The
  species-level companion to `genus_mismatch`.
- **`genus_year_after_species`** (INFO): ACCEPTED usage with a `publishedInYear` and a nearest
  accepted genus ancestor whose `publishedInYear` is **greater** (genus published after the species).
- **`synonym_of_non_accepted`** (ERROR): SYNONYM/MISAPPLIED usage with ≥1 `synonym_accepted` target
  whose usage is not ACCEPTED (`synonymNonAcceptedTargetCount > 0`).

`RuleContext` new fields: `parentRank` (String), `ancestorGenusYear` (Integer),
`ancestorSpeciesEpithet` (String), `synonymNonAcceptedTargetCount` (int). Canonical ctor takes all;
keep the existing 4-arg and 5-arg(+ancestorGenusName) convenience ctors delegating with defaults.

`NameUsageMapper` new queries: `findRank(pid,id)`; `findAncestorGenusYear(pid,id)` and
`findAncestorSpeciesEpithet(pid,id)` (recursive CTEs up `parent_id`, depth>0, first `rank='genus'` /
`rank='species'`); `countNonAcceptedSynonymTargets(pid,synonymId)` (join `synonym_accepted` →
`name_usage` where target status != 'ACCEPTED'). buildContext calls these per usage (fine for current
project sizes; batching is a later optimization).

**Tests:** unit `RuleTests` for each rule (hand-built contexts); mapper ITs for the recursive CTEs +
the non-accepted-target count.

## B. Issues dashboard (frontend, new section **Issues**)

- `api/issues.ts` gains `listIssues(pid, {status?, severity?, limit, offset})`,
  `issueSummary(pid)`, `reviewIssue(pid, id, action)`, `revalidate(pid)`. Types: `IssueRow`
  (matches backend `IssueResponse`), `IssueSummary` (`{total, byStatus, bySeverity}`).
- **`IssuesPage`**: a header rollup (Badges: total + per-severity error/warning/info + per-status
  open/accepted/rejected/done) with a **Revalidate** button (owner/editor; POSTs `/revalidate`,
  refreshes). Below, a table with **Status** + **Severity** filter Selects; columns
  rule · severity (colored badge) · message · entity (`type #id`, links to the tree/detail) · status.
  Each row (owner/editor) has **Accept / Reject / Reopen** (a `⋮` menu calling `reviewIssue`).
  Server-paged like `NameSearchPage` (reuse the MRT pattern; `List` response, so paginate on
  limit/offset with a "load more"/page control — keep it simple: a fixed page size + prev/next).
- Read for any member; review/revalidate gated to owner/editor.

## C. History view (frontend, new section **History**)

- `api/changes.ts`: `listChanges(pid, {taskId?, limit, offset})` → `Change[]` (`id, username, at,
  entityType, entityId, operation, diff (JSON string), taskId`); `listTasks(pid)` for the task filter.
- **`HistoryPage`**: reverse-chronological list (the API returns newest-first). Each entry: an
  operation `Badge` (create=green / update=blue / delete=red), the entity (`entityType #entityId`),
  the `username`, a relative timestamp (dayjs, already a dep), and a collapsible **diff** rendered as
  pretty-printed JSON (`Collapse` / `Spoiler`). A **Task** filter Select (from `listTasks`) drives
  `taskId`. Prev/next paging.
- Read for any member.

## Nav / routing

Add to `AppSidebar` (after Names): **Issues** (`IconAlertTriangle`, `/projects/:id/issues`) and
**History** (`IconHistory`, `/projects/:id/history`). Add the two routes under `ProjectLayout` in
`App.tsx`.

## Testing / verification

Backend `mvn verify` (rules unit + mapper ITs). Frontend `npm test` + build (api client tests;
IssuesPage: renders summary, filters, review action POST; HistoryPage: renders entries + diff, task
filter). Browser: on seeded Felidae, open Issues (the `missing_published_in` INFOs show; Revalidate
works; accept one), open History (the P2/P3 edits appear with diffs).

## Out of scope

Bulk issue actions; changelog revert; issue assignment to tasks from the UI; the `dangling_pointer`
rule (FK-enforced).
