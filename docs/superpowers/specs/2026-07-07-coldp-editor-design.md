# ColDP Editor — Design Specification

**Status:** Draft for review
**Date:** 2026-07-07
**Author:** Markus Döring (with Claude)

## 1. Summary

A convenient, collaborative, web-based editor for taxonomic and nomenclatural
data built around the [ColDP](https://github.com/CatalogueOfLife/coldp) data
format. A single instance hosts **many projects**, each owned and edited by a
set of users. Projects range in size from a single genus (~100 species) to
large groups such as the whole Lepidoptera (~200,000 accepted species plus a
larger number of synonyms).

The editor is **standalone** — it has its own database and does not depend on
the ChecklistBank backend at runtime — but it **reuses selected CoL/GBIF Java
libraries** (GBIF `name-parser`, `NameFormatter`, and the ColDP reader/writer)
so it shares parsing, formatting and I/O behaviour with the wider CoL
ecosystem. Data leaves and enters the system as ColDP archives.

The central entities that get the most attention, especially early on, are
**References**, **Names**, and **NameUsages**. The classification is built as a
**parent/child hierarchy** (`NameUsage.parent_id`); the flat, denormalised
higher-rank columns of the ColDP `Taxon` entity are deliberately **not** used.

## 2. Goals and non-goals

### Goals
- Host multiple independent projects with per-project users and roles on one instance.
- Comfortable editing of References, Names, and NameUsages, and the parent/child classification tree, at genus-to-Lepidoptera scale.
- Track who edited what, with a recent-changes log and the ability to revert.
- Async multi-user collaboration with optimistic concurrency and soft (advisory) locks on records/subtrees.
- Async, **non-blocking validation**: users may enter bad data and are warned *softly* about problems (never hard-blocked).
- ColDP import (with entity selection and dedup/merge) and export (later phases).
- Batch/bulk editing where useful (later phase).
- Per-project ColDP metadata management.

### Non-goals
- Real-time character-level co-editing (no CRDT/OT, no live cursors).
- Being a public read/search portal — this is an editing tool. Published data is exported to ColDP / ChecklistBank.
- Runtime coupling to the ChecklistBank backend or its database.
- Storing or maintaining the denormalised flat higher-rank classification columns.
- Managing `accordingTo` on usages, or a separately-managed scrutinizer (see §5.5).

## 3. Technology stack

| Layer | Choice |
|---|---|
| Backend | **Java 21, Spring Boot** |
| Persistence | **MyBatis** (`mybatis-spring-boot-starter`) — hand-written SQL |
| Database | **PostgreSQL 17** (single DB, shared schema) |
| Migrations | **Flyway** |
| Auth | **Spring Security**, ORCID OAuth2/OIDC (primary), local accounts (fallback) |
| Domain libs | GBIF **`name-parser` 4.2.0-SNAPSHOT** (+ `name-parser-api`, which also carries `NameFormatter`), ColDP reader/writer — see [Appendix A](#appendix-a--name-parser-420-snapshot-integration) |
| Search | PostgreSQL only — `pg_trgm` (GIN) + btree indexes; no Elasticsearch |
| Frontend | **React + TypeScript + Vite + Ant Design**, TanStack Query |
| Tree UI | Virtualized, lazy-loaded tree (e.g. react-arborist / react-window) |

**Rationale.** Spring Boot makes ORCID OIDC login nearly turnkey (the largest
non-domain task in phase 1) via Spring Security, while `mybatis-spring-boot-starter`
preserves hand-written SQL for the tree, bulk, and audit operations where an ORM
would fight us. It aligns with GBIF (which uses Spring Boot for new projects) for
shared patterns and support. Postgres-only search avoids Elasticsearch's
operational burden and write-sync lag; `pg_trgm` handles fuzzy/prefix name search
well at the ~1M-name scale of a single large project. The frontend mirrors the
checklistbank UI (React/Ant Design) for team familiarity.

## 4. Architecture and multi-tenancy

- **One Postgres database, shared schema.** Every data row carries a
  `project_id`. Roles are per-project. This supports many projects with easy
  cross-project lookups and simple operations. The large tables (`name`,
  `name_usage`) can be **partitioned by `project_id`** later if a single project
  grows very large; the schema is designed so this is a non-breaking change.
- **REST/JSON API** served by Spring Boot; the React SPA is the only client.
- **ORCID OAuth2** for login; a signed session/JWT authorises API calls.
- **Server-side tree traversal.** Children are fetched lazily per node and
  paginated, so a 200k-node classification is never materialised at once in the
  browser or the API response.

## 5. Data model

The model follows the ChecklistBank backend's *internal* normalised model, not
the flat ColDP export file. **Name and NameUsage are separate entities.** Column
lists below are indicative, not exhaustive; full DDL lives in Flyway migrations.

### 5.1 Project, users, membership

- **`project`** — `id`, `key/slug`, ColDP metadata (title, alias, description,
  license, version, issued, geographic/taxonomic scope, DOI, etc. as structured
  columns plus a JSONB blob for the long tail of extensible YAML fields),
  **`nom_code`** (the single nomenclatural code applied to *all* names in the
  project), timestamps.
- **`app_user`** — `id`, `orcid` (nullable for local accounts),
  `username/email`, display name, `family`/`given` (for authorship),
  password hash (local accounts only), timestamps.
- **`project_member`** — (`project_id`, `user_id`, `role`) where role ∈
  {owner, editor, reviewer, viewer}.

The project's `nom_code` drives `NameFormatter` rendering and any code-specific
behaviour or validation. There is **no per-name `code` column** — it is derived
from the project.

### 5.2 Reference

- **`reference`** — project-scoped, CSL-based: `id`, `project_id`,
  `alternative_id[]`, `citation` (formatted), `type` (CSL type), `author`,
  `editor`, `title`, `container_title`, `issued`, `volume`, `issue`, `page`,
  `publisher`, `doi`, `isbn`, `issn`, `link`, `remarks`, plus `modified` /
  `modified_by`. Structured fields + a formatted citation string.

### 5.3 Author

- **`author`** — project-scoped normalised persons (the ColDP Author entity):
  `id`, `project_id`, `alternative_id[]` (incl. `orcid:` scope), `given`,
  `family`, `suffix`, `abbreviation_botany`, `affiliation`, `link`, `remarks`,
  `modified` / `modified_by`. A logged-in user's ORCID links a user to an
  `author` record so authorship/attribution stay consistent.

### 5.4 Name (nomenclature)

- **`name`** — pure nomenclatural facts, project-scoped:
  - `id`, `project_id`, `alternative_id[]`, `basionym_id` (self-ref)
  - `scientific_name`, `authorship`, `rank`
  - atomized parts: `uninomial`, `genus`, `infrageneric_epithet`,
    `specific_epithet`, `infraspecific_epithet`, `cultivar_epithet`, `notho`
  - atomized authorship: `combination_authorship`, `combination_ex_authorship`,
    `combination_authorship_year`, `basionym_authorship`,
    `basionym_ex_authorship`, `basionym_authorship_year`
  - `nom_status` (nomenclatural status), `published_in_reference_id`,
    `published_in_year`, `published_in_page`, `gender`, `etymology`, `link`,
    `remarks`, `modified` / `modified_by`
  - **No `code` column** — derived from `project.nom_code`.
- On entry the **GBIF name-parser** atomizes `scientific_name` + `authorship`
  into the parts above; the user can also edit atomized fields directly. The
  parser API in 4.2.0-SNAPSHOT differs substantially from older versions — the
  exact contract we bind to is documented in
  [Appendix A](#appendix-a--name-parser-420-snapshot-integration).
- Display strings are produced by GBIF **`NameFormatter`** using the project code.

### 5.5 NameUsage (taxonomic usage)

- **`name_usage`** — the taxonomic usage of a name; this table **is the
  classification**:
  - `id`, `project_id`, `alternative_id[]`
  - `name_id` → `name`
  - `parent_id` → `name_usage` (self-ref) — **builds the parent/child tree**
  - `status` ∈ {accepted, provisionally accepted, synonym, ambiguous synonym,
    misapplied, bare name}
  - `name_phrase` (free-text qualifier, e.g. `auct. non …`, `sensu lato`)
  - `reference_id[]` (taxonomic references supporting the usage)
  - `extinct`, `environment[]`, `temporal_range_start/end`, `ordinal`
    (sibling sort order), `link`, `remarks`, `modified` / `modified_by`
  - **No `according_to_reference_id` / `accordingToPage`** (dropped per decision).
  - **No scrutinizer fields** — the scrutinizer is derived from the audit log
    (last editor + `modified` timestamp) whenever it needs to be shown or
    exported.
  - Accepted usages `parent_id` → an accepted usage; **synonyms `parent_id` →
    the accepted usage they belong to** (mirrors ColDP merged-NameUsage
    semantics). Bare names have no parent.
- **Subtree operations** (move subtree, descendant counts, subtree locking):
  `name_usage` carries a **Postgres `ltree` materialized path** (or a closure
  table) maintained on insert/move, so subtree queries and locks are cheap and
  don't require deep recursive scans on the hot path.

### 5.6 Supporting entities

Schema present from the start; **editing UX phased in later** (phase 5):
`name_relation`, `type_material`, `distribution`, `vernacular_name`,
`taxon_property`, `species_estimate`, `species_interaction`,
`taxon_concept_relation`, `media`. All project-scoped with `modified` /
`modified_by`.

### 5.7 Audit / change log

- **`change`** (append-only) — `id`, `project_id`, `user_id`, `at` (timestamp),
  `entity_type`, `entity_id`, `operation` ∈ {create, update, delete},
  `diff` (JSONB field-level before/after). Powers "who edited what", the
  recent-changes view, and revert. Entity rows also carry ColDP-native
  `modified` / `modified_by` for export fidelity.

### 5.8 Locks (soft / advisory)

- **`lock`** — `id`, `project_id`, `entity_type`, `entity_id` **or** subtree
  `path` (ltree), `user_id`, `acquired_at`, `expires_at`. Locks are advisory
  (surfaced in the UI so others see a record/subtree is being worked on) and
  **auto-expire**. They coordinate hand-editing of shared groups; they do not
  hard-block writes — the optimistic version check is the real safety net.

### 5.9 Concurrency

- Every editable row carries a `version` (or reuses `modified`) for
  **optimistic locking**: a save includes the version the client last read; a
  mismatch returns a conflict and the UI warns/refreshes rather than silently
  overwriting.

### 5.10 Issues (validation findings)

- **`issue`** — a validation finding attached to an entity: `id`, `project_id`,
  `entity_type`, `entity_id`, `rule` (stable rule key), `severity` ∈
  {info, warning, error}, `message`, `context` (JSONB, e.g. the offending
  value), `created_at`, and `dismissed_at` / `dismissed_by` (nullable, for
  findings a user has accepted as-is). **Severity is advisory only — no severity
  ever blocks a save.** Findings are recomputed by the validation subsystem
  (§6), not hand-entered; a finding that no longer holds simply disappears on
  the next recompute (no separate "resolved" state), while a dismissal
  suppresses a still-true finding until the underlying data changes.

## 6. Async validation subsystem

Validation is **decoupled from editing**. Saving is never blocked by data
problems; instead, rules run **asynchronously** and attach `issue` records
(§5.10) that the UI surfaces as soft warnings.

- **Trigger.** Each create/update/delete emits a change event (same source as
  the audit log). A background worker consumes events and (re)validates the
  affected entity plus a bounded set of directly related entities (e.g. editing
  a `name` re-checks its `name_usage`; reparenting re-checks the moved node and
  its new/old parent). A **full project re-validation** can also be run on
  demand (and after import in phase 2).
- **Execution model.** Rules run out-of-band on a worker pool / queue so large
  edits and bulk operations don't stall the UI. Results are written idempotently
  (a rule replaces its own prior findings for an entity), so re-running is safe.
- **Rule engine.** Rules are small, independent, individually testable units
  with a stable `rule` key and a default severity. Each rule declares what
  entity types it applies to and what related data it needs. New rules can be
  added without schema changes. Where possible we reuse the **CoL/GBIF
  validation logic** (the backend already flags many name/usage issues) rather
  than reinventing checks.
- **Severity is soft.** `error` means "almost certainly wrong", `warning` means
  "looks suspicious", `info` is advisory — but all are non-blocking. Users can
  **dismiss** a finding (accept-as-is), which suppresses it until the underlying
  data changes again.
- **Surfacing.** Issue counts roll up in the tree (a node shows its own +
  descendant issue badges) and appear inline on record forms and in a per-project
  "problems" view filterable by rule/severity/entity type.
- **Starter rule set (phase 1)** — the infrastructure plus a small set, e.g.:
  unparsable scientific name; authorship/year mismatch with the linked reference;
  rank inconsistent with parent rank; synonym whose parent is itself a
  synonym; missing `published_in` reference; duplicate scientific name +
  authorship within a project; dangling reference/name/parent pointers. The rule
  catalogue grows over time.

## 7. Phase 1 scope (first working version)

The buildable slice:

1. **Multi-project + auth**: ORCID OAuth2 login (local-account fallback),
   per-project roles (owner/editor/reviewer/viewer), project switcher.
2. **Project ColDP metadata** editing, including selecting the single
   `nom_code`.
3. **References**: CRUD + fuzzy search.
4. **Names**: CRUD + fuzzy search, GBIF name-parser auto-atomization on entry,
   `NameFormatter` rendering using the project code.
5. **NameUsages + classification tree**: create / rename / move / change-status,
   lazy-loaded and virtualized, sibling ordering; synonyms attach to their
   accepted usage.
6. **Soft locks** on records/subtrees + optimistic-locking conflict handling.
7. **Audit log** with a per-project recent-changes view and single-record
   revert.
8. **Async validation infrastructure** (§6) + the starter rule set, with soft
   issue badges in the tree/forms and a per-project problems view.

Explicitly **out** of phase 1: ColDP import, ColDP export, batch/bulk editing,
supporting-entity editing UX. (The validation *engine* is in; the rule
*catalogue* keeps growing in later phases.)

## 8. Phasing roadmap (after v1)

- **Phase 2 — ColDP import.** Upload a ColDP archive; choose which entities to
  import; **dedup/merge** against existing records. Matching keys: shared
  identifiers first, then parsed name + author/year (via name-parser) for names,
  DOI/title for references. Present a per-record **merge / skip / replace**
  decision UI, with a bulk "apply to all similar" affordance.
- **Phase 3 — ColDP export.** Write a valid ColDP ZIP (normalized entity files +
  `metadata.yaml`) via the ColDP writer library. Round-trips `modified` /
  `modified_by` and derives scrutinizer from the audit log.
- **Phase 4 — Batch editing.** Multi-select in tree/search → bulk change
  rank/status/parent, move subtrees, find-and-replace authorship, bulk delete;
  every operation captured in the audit log and revertible.
- **Phase 5 — Supporting-entity editing UX** (type material, distributions,
  vernacular names, relations, etc.) + reference enrichment (DOI/CrossRef
  lookup, BibTeX/CSL-JSON import).

## 9. Key risks and mitigations

- **Tree at scale (200k+ nodes).** Mitigate with server-side lazy loading,
  pagination, `ltree` materialized paths for subtree ops, and virtualization in
  the UI. Never load a whole classification at once.
- **Move/reparent correctness** with materialized paths: subtree path rewrites
  must be transactional and audited. Covered by tests on move operations.
- **Dedup/merge quality** (phase 2): matching must be reviewable, never silently
  destructive; default to "skip" and require explicit merge/replace.
- **ORCID auth edge cases**: users without ORCID (local fallback) and linking an
  ORCID to an existing local user.
- **Validation worker lag/backlog** under bulk edits: findings may briefly trail
  the data. Acceptable because validation is advisory; the UI marks stale/pending
  validation, and idempotent re-runs converge.

## 10. Decisions on record

- Standalone app; reuse CoL/GBIF Java libraries (name-parser, NameFormatter, ColDP I/O).
- Async multi-user with optimistic locking **and** soft (advisory) subtree/record locks.
- Phase 1 = foundation + core editing; import/export/batch deferred.
- ORCID login primary, local accounts as fallback; ORCID doubles as the authorship identifier.
- Backend: Spring Boot + MyBatis + Postgres + Flyway + Spring Security.
- Postgres-only search (`pg_trgm`), no Elasticsearch.
- Normalized Name / NameUsage split; parent/child classification only, flat higher ranks ignored.
- `ltree` materialized path for subtree operations.
- Single `nom_code` per project (drives NameFormatter and code-specific behaviour); no per-name code.
- `accordingTo` dropped from usages; scrutinizer derived from the audit log, not managed separately.
- Async, non-blocking validation: rules run out-of-band and attach advisory `issue` findings; bad data is allowed and only softly flagged. Engine + starter rules land in phase 1.
- Name parsing/formatting binds to GBIF **name-parser 4.2.0-SNAPSHOT**, whose API differs substantially from older releases (see Appendix A). Only name-level combination/basionym authorship is used; per-epithet `genericAuthorship`/`specificAuthorship` are ignored.

## Appendix A — name-parser 4.2.0-SNAPSHOT integration

We depend on the **4.2.0-SNAPSHOT** GBIF name-parser, whose API differs
substantially from the 3.x / earlier 4.x releases. The implementation and any
future work must bind to *this* contract, verified against the local checkout at
`~/code/gbif/name-parser`. Do **not** assume the older `NameParserGBIF`
singleton / two-arg `parse` shapes.

**Coordinates.** `org.gbif:name-parser:4.2.0-SNAPSHOT` (impl) and
`org.gbif:name-parser-api:4.2.0-SNAPSHOT` (types + `NameFormatter` +
`RankUtils`). Being a SNAPSHOT, it is sourced from the GBIF snapshots repository
or a local `mvn install` of the checkout; pin the resolved timestamped snapshot
in CI for reproducibility.

**Instantiation.** `NameParser parser = new NameParserImpl();` — a direct
constructor, not a static singleton. The instance is thread-safe and reusable;
create one bean and share it.

**Parsing entry point** (`org.gbif.nameparser.api.NameParser`):

```java
ParsedName parse(String scientificName,
                 @Nullable String authorship,   // separate authorship, may also be inline in the name
                 @Nullable Rank rank,           // pass the known rank when we have it
                 @Nullable NomCode code)         // pass the project's nom code
    throws UnparsableNameException;
```

Pass the project `nom_code` and the usage's `rank` on every call. The older
single-/two-arg overloads are `@Deprecated`. `parseAuthorship(String, NomCode)`
parses authorship alone and returns a `ParsedAuthorship`.

**Authorship model (the biggest change).** Authorship is a
**`CombinedAuthorship`** (implementing `CombinedAuthorshipIF`) bundling a
combination `Authorship`, a basionym `Authorship`, and an optional
`sanctioningAuthor`; each `Authorship` carries authors, ex-authors, and year.
`ParsedName extends ParsedAuthorship`, and the **name-level** authorship we use
lives on that superclass: `getCombinationAuthorship()`,
`getBasionymAuthorship()`, `getSanctioningAuthor()`.

**We deliberately ignore the per-epithet `getGenericAuthorship()` /
`getSpecificAuthorship()` getters.** Those attach authors to a non-terminal
epithet (e.g. a genus author cited inside a larger name) and are not part of a
proper name for our purposes. Only the name-level combination/basionym
authorship above is mapped and stored.

Mapping `ParsedName` → ColDP `name` columns:

| ColDP `name` field | Source |
|---|---|
| `combination_authorship` / `combination_ex_authorship` / `combination_authorship_year` | `getCombinationAuthorship()` → `getAuthors()` / `getExAuthors()` / `getYear()` |
| `basionym_authorship` / `basionym_ex_authorship` / `basionym_authorship_year` | `getBasionymAuthorship()` → `getAuthors()` / `getExAuthors()` / `getYear()` |
| `uninomial`, `genus`, `infrageneric_epithet`, `specific_epithet`, `infraspecific_epithet`, `cultivar_epithet` | the corresponding `ParsedName` getters (`getEpithet(NamePart)` for epithet-level access) |
| `notho` | `getNotho()` → `Set<NamePart>` |
| `rank` | `getRank()` (`Rank` enum) |

**Rendering** — `org.gbif.nameparser.util.NameFormatter` (static):
`canonical(ParsedName)`, `canonicalWithoutAuthorship`, `canonicalMinimal`,
`canonicalComplete`, `canonicalCompleteHtml` for HTML (italics), and
`authorshipComplete(ParsedName)` / `authorString(Authorship, boolean inclYear,
NomCode)` for authorship. `buildName(...)` is the fully parameterised builder.
All honor the `NomCode`, so pass the project code for code-correct rendering.

**Enums / vocabularies** come from `name-parser-api`: `Rank`, `NomCode`,
`NamePart`, `NameType`. Our `name.rank`, `project.nom_code`, and related
vocabularies should map to these enums (or store the enum name) rather than
defining parallel lists.

**Parse quality → validation.** `parse(...)` throws
`UnparsableNameException` (carrying a `NameType`, e.g. virus / hybrid-formula /
placeholder) for names that cannot be atomised — the "unparsable scientific
name" starter rule catches this. `ParsedName` also exposes a `State`
(`COMPLETE` / `PARTIAL` / …) and warnings; a non-`COMPLETE` state feeds a
"doubtful parse" warning finding.
