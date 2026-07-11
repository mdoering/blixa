# Bulk name insert — design

**Status:** approved design, ready for planning
**Date:** 2026-07-11

## Goal

Let an editor add many names at once instead of one at a time, in two size
regimes that share one input format:

- **Path A (sync):** paste or upload a small list/tree and insert it
  **directly under a chosen target taxon** in the current project, after a
  preview-and-confirm step. Bounded and immediate.
- **Path B (async):** upload a large tree and run it through the **existing
  ColDP import pipeline as a new staging project**, then reconcile it into the
  target project with the **existing supervised merge**. Scales to big trees,
  no new async machinery.

Both paths take the same input grammar: the GBIF **text-tree** format, of
which *a plain list of names is the degenerate case*.

## Input format (GBIF text-tree, `org.gbif:text-tree` 1.7.0)

We use the library, not a hand-rolled parser. Confirmed behaviour of
`Tree.simple(Reader)` / `Tree.verify(...)` against the 1.7.0 jar:

- **A plain list of names — one per line, no indentation — parses as all root
  nodes.** This is the low-friction path; nothing special is needed for it.
- **Rank is declared per line with a `[rank]` suffix** (`Panthera leo
  [species]` → name `Panthera leo`, rank `species`). When omitted, `rank` is
  `null` and we fall back to name-parser inference (see below).
- **Hierarchy** is expressed with **exactly 2 spaces of indentation per
  level**. Tabs and over-indent jumps are rejected with a clear, line-numbered
  message.
- **Prefix markers** (from `Tree.*_SYMBOL`): `=` (and legacy `*`) → **synonym**
  of the nearest accepted ancestor (lands in `node.synonyms`, authorship
  preserved, with `homotypic`/`basionym` booleans); `†` (U+2020) → `extinct`;
  `?` → `provisional`; `≡` → homotypic; `$` → basionym.
- Blank lines and trailing whitespace are tolerated.
- `SimpleTreeNode` fields we consume: `name`, `rank`, `extinct`,
  `children` (accepted), `synonyms`. `id` is a line-derived `long`.
- `Tree.verify` returns `{boolean valid, int lines, String message}`;
  `Tree.simple` throws `IllegalArgumentException` with a line-numbered message
  on the first malformed line. Both feed the preview's blocking errors.

**UI note:** because indentation must be 2 spaces, the modal's help text must
say so, and the preview must surface the library's line-numbered error
verbatim. (Auto-converting tabs→2-spaces is explicitly out of scope for v1 —
see Future.)

## Reused building blocks (already in the codebase)

- `NameUsageService.create` semantics: accepted usage = `status=ACCEPTED`,
  `parentId=<parent>`; synonym = `status=SYNONYM`, `parentId=null`, then linked
  to its accepted via `SynonymAcceptedMapper.link` (the same two-step the
  single-add UI uses). Status enum: `ACCEPTED / SYNONYM / MISAPPLIED /
  UNASSESSED` (no provisional/misapplied text-tree marker maps to a distinct
  status in v1).
- `NameParserService.parseInto(usage, nomCode)` — atomizes the name and infers
  rank/nameType/parseState.
- `IdSeqMapper.allocate(projectId, ENTITY)` — per-project id generation.
- `TreeMapper.lockProject(projectId)` — advisory xact lock serializing tree
  mutations.
- Audit (`audit.record`) and validation events (`ValidationEvent.forUsage`,
  fired inside the transaction, AFTER_COMMIT listener).
- **ColDP import pipeline** (`coldp/imprt/ImportRunService`): `start()` inserts
  a RUNNING `import_run`, extracts into a temp `dir` on the request thread,
  then hands `dir` to the `@Async run()` → `loadTransactional` →
  `ColdpReader.from(dir)` → **creates a new (staging) project** and loads all
  entities. Recovery (`ImportRunRecovery`) and the poll/progress UI
  (`ImportProjectModal`, `api/import.ts`) already exist.
- **Supervised merge** (`merge/`, `MergeModal`): project→project reconciliation
  with exact/fuzzy matching, impact review, and overwrite/fill/skip modes.
- ColDP writing: `coldp/export/NameUsageColdpWriter` already writes a ColDP
  `NameUsage` table via `TermWriter`/`ColdpTerm` — the converter reuses the
  same machinery.

## Path A — synchronous insert under a target taxon

### Flow

1. `NameActionMenu` on a taxon gains a **"Bulk add…"** item → opens
   `BulkAddModal(targetId)`.
2. The modal has: a **textarea** (paste), a **file picker** (read into the
   same text string client-side — no multipart needed), a **mode toggle**
   (*As accepted children* [default] / *As synonyms of the target*), and help
   text describing the format (one name per line; `[rank]`; 2-space indent;
   `=` synonym; `†` extinct).
3. **Preview** → `POST /api/projects/{pid}/usages/bulk/preview` with
   `{targetId, mode, text}`. The server parses via `Tree.simple`, resolves each
   node's *effective rank* and *status*, flags duplicates, counts totals, and
   returns a structured tree. **No writes.**
4. The modal renders the parsed tree indented, with per-node rank + status
   badges, an extinct marker, and duplicate warnings, plus a summary
   ("42 accepted, 8 synonyms; 3 already exist under this taxon"). A parse error
   (line-numbered) or an over-cap count **blocks** the confirm button.
5. **Confirm** ("Insert N names") → `POST /api/projects/{pid}/usages/bulk` with
   the same `{targetId, mode, text}`. The server **re-parses and re-validates**
   (never trusts client-sent parse output), re-checks the cap, and inserts.

### Rank / status resolution per node

- **Rank:** `node.rank` if the `[rank]` suffix was present; else the
  name-parser-inferred rank; else `unranked`. The effective rank is shown in
  the preview so the user can catch a misread before confirming.
- **Status:** a `children` node → `ACCEPTED`; a `synonyms` node → `SYNONYM`.
  `extinct` → `extinct=true`. `provisional`/`homotypic`/`basionym`/`misapplied`
  are **not** distinctly modeled in v1 (parsed but ignored; see Future).
- **Synonymy mode** (`As synonyms of the target`): valid only for a **flat**
  input. Every top-level node becomes a `SYNONYM` linked to the target. If the
  input is indented (any node has children/synonyms) in this mode, the preview
  returns a blocking validation error.

### Insert semantics

- One `@Transactional`, all-or-nothing. `TreeMapper.lockProject` once.
- Accepted node → build `NameUsage(status=ACCEPTED, parentId=<parent>)`,
  `parseInto`, `idSeq.allocate`, `usages.insert`, write taxon-info,
  `audit.record(CREATE)`, publish `ValidationEvent`.
- Synonym node → `NameUsage(status=SYNONYM, parentId=null)` inserted the same
  way, then `SynonymAcceptedMapper.link(synId, acceptedAncestorId)` +
  audit/validation for the link. (Text-tree synonyms carry no children of their
  own in `simple`; if any appear they are ignored with a preview warning.)
- **Duplicates are inserted anyway** (product decision): the preview flag is
  purely informational; nothing is skipped or blocked on a duplicate.
- **Cap:** ≤ **1000** total names (accepted + synonyms across the whole tree).
  Over the cap → `400` "This list is too large for a direct insert (N > 1000).
  Import it as a new dataset instead." — surfaced in the preview as a blocking
  error that points the user at Path B.
- Authz: editor role required (`requireEditor`), like `create`.

### Backend components (Path A)

- `name/bulk/BulkInsertService` — parse (`Tree.simple`), validate
  (`Tree.verify` + mode/cap/target checks), resolve rank/status, duplicate
  scan, and the transactional insert. Reuses the building blocks above.
- `name/bulk/BulkInsertController` — `POST …/usages/bulk/preview` and
  `POST …/usages/bulk` under the existing usages path.
- DTOs: `BulkInsertRequest(int targetId, String mode, String text)`;
  `BulkPreviewResponse(boolean valid, String error, int total, int accepted,
  int synonyms, int duplicates, List<PreviewNode> nodes)`;
  `PreviewNode(String name, String rank, String status, boolean extinct,
  boolean duplicate, List<PreviewNode> children, List<PreviewNode> synonyms)`;
  `BulkInsertResult(int created, int synonymsLinked, int rootId /*target*/)`.
- Duplicate detection: canonical-name match (name-parser canonical, case-
  insensitive) of each **top-level** node against the target's existing
  children (children mode) or existing synonyms (synonymy mode). Nested new
  nodes have new parents, so no existing-duplicate is possible there.

### Frontend components (Path A)

- `names/BulkAddModal.tsx` — textarea + file input + mode toggle + preview tree
  render + confirm. Mirrors the existing modal patterns (`CreateNameModal`,
  `MergeModal`).
- `api/bulk.ts` — `previewBulk(pid, body)`, `insertBulk(pid, body)`.
- A **"Bulk add…"** `Menu.Item` in `NameActionMenu` (gated to editors,
  alongside "Add child"/"Add synonym").
- On success: close, `notifications.show`, and invalidate the target's
  children/tree queries so the new names appear.

## Path B — asynchronous import as a staging dataset

For trees over the Path-A cap. The tree becomes its **own new project**
(roots → project roots), imported through the **existing** ColDP pipeline, then
reconciled into the real target via the **existing** merge. No new async
machinery, no importer changes.

### Flow

1. Entry lives at the **project-import level** (where ColDP import lives), not
   on a taxon. `ImportProjectModal` gains a text-tree option (accept
   `.txtree` / `.txt` / `.tsv`, plus a staging-project title).
2. Backend validates via `Tree.verify`, inserts a RUNNING `import_run`, and
   **converts the text-tree to a ColDP `NameUsage.tsv`** in the import temp
   `dir` (new `TxtTreeToColdp` writer, reusing `TermWriter`/`ColdpTerm`). Each
   node → one row: `ID` (line id), `parentID` (accepted parent; for a synonym,
   its accepted ancestor's id), `status` (accepted/synonym), `rank` (effective
   rank), `scientificName`, `extinct`.
3. It calls the **existing** `ImportRunService.run(runId, dir, …)` — same
   `@Async` body, staging-project creation, recovery, and poll/progress UI. The
   only new backend surface is the conversion + a text-tree branch on the
   import controller/service that produces `dir` instead of unzipping.
4. The user reconciles the staging project into their target with the existing
   `MergeModal` (matching + impact review + overwrite/fill/skip). No new merge
   code.

### Consequences (accepted)

- Path B lands as a **standalone staging project**; it is **not** anchored to a
  target taxon. So a Path-B tree should carry its own higher classification;
  attachment into the real project is by name-matching during the merge —
  identical to how a ColDP import is reconciled.
- Path A remains the taxon-anchored, direct-insert route for small trees.

### Components (Path B)

- Backend: `coldp/imprt/TxtTreeToColdp` (converter); a text-tree entry on
  `ImportRunService`/`ImportRunController` (route by file type → build `dir` →
  reuse `run()`); reuse everything else.
- Frontend: extend `ImportProjectModal` to offer text-tree upload (or a sibling
  modal), reusing `api/import.ts` polling.

## Shared foundation

- Add dependency `org.gbif:text-tree:1.7.0` (transitively pulls
  `commons-lang3`, `commons-io`, `slf4j-api`, `name-parser*` — versions already
  on the project classpath).
- A small internal helper for **effective rank/status resolution** and
  **canonical-name** computation is shared by Path A's inserter and Path B's
  converter so the two paths agree on how a node maps to a usage row.

## Error handling

- Parse/verify failure → `400` with the library's line-numbered message; the
  preview shows it and blocks confirm.
- Empty input, unknown/foreign target, non-editor → clear `4xx` (reuse
  `requireEditor`, `requireValidParent`/project checks).
- Path A insert is all-or-nothing; a mid-insert failure rolls the whole
  transaction back. Validation events fire only after commit, so Issues
  populate exactly as with single creates.
- Path B failures use the existing import failure handling (`import_run` marked
  FAILED, temp `dir` cleaned up, poll surfaces the error).

## Testing strategy

**Path A (backend, `AbstractPostgresIT`):**
- plain list → N accepted children under the target;
- `[rank]` honored; missing rank → parser-inferred; unparsable → unranked;
- indented tree → nested accepted hierarchy with correct parentIds;
- `=` synonym → `SYNONYM` linked to its accepted ancestor;
- `†` → extinct;
- synonymy mode on a flat list → synonyms linked to target; synonymy mode on
  indented input → blocking validation error;
- cap exceeded → `400` (nothing inserted);
- duplicate present → flagged in preview but still inserted (count reflects it);
- preview endpoint writes nothing (row counts unchanged);
- non-editor → `403`;
- mid-insert failure → full rollback.

**Path A (frontend):** modal renders the preview tree from a stubbed preview
response; confirm calls `insertBulk` and invalidates queries; parse-error
response disables confirm.

**Path B (backend):** `TxtTreeToColdp` converts a representative tree to a
`NameUsage.tsv` whose columns/values are correct (parentID linkage,
synonym→accepted parentID, status, rank, extinct); an end-to-end IT feeds a
converted `dir` through `run()` and asserts the staging project's usages/tree
match the input. Reuse the existing import ITs' harness.

**Path B (frontend):** import modal accepts a text-tree file and starts a run
(reusing the ColDP-import test patterns).

## Out of scope / future

- Auto-converting tab indentation to 2 spaces (v1 documents the requirement and
  surfaces the clear error instead).
- Distinct handling of `provisional` / `misapplied` / `homotypic` / `basionym`
  markers (parsed, not modeled in v1).
- Root-level Path-A insert with no parent (top-of-project). Path A always
  targets an existing taxon; project-root additions go via Path B or the
  single-add "Add accepted name".
- References / vernaculars / distributions in the text-tree input (names only).

## Decomposition / phasing

One spec, two independently shippable phases sharing the text-tree foundation:

- **Phase 1 — Path A (sync, taxon-anchored):** dependency, parsing/resolution
  helper, `BulkInsertService`/controller/DTOs, `BulkAddModal`, menu item,
  tests. Delivers working bulk insert on its own.
- **Phase 2 — Path B (async staging import):** `TxtTreeToColdp`, the text-tree
  branch on the import controller/service, the `ImportProjectModal` extension,
  tests. Reuses import + merge; delivers large-tree import on its own.
