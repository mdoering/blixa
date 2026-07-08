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
libraries** — GBIF `name-parser` / `NameFormatter` for name atomisation and
rendering, and the CoL `vocab` (enums) and `coldp` (terms) modules — so it
shares parsing, formatting and vocabularies with the wider CoL ecosystem. Data
leaves and enters the system as ColDP archives (the archive reader/writer is
added in phases 2–3).

The central entities that get the most attention, especially early on, are
**References** and **NameUsages** (with **Authors** alongside). Nomenclatural
name facts and taxonomic usage are **collapsed into a single `name_usage`
entity** — in this editor the Name↔usage relation is always 1:1, so keeping
them apart only adds a join and a second form (see §5.0). The classification is
built as a **parent/child hierarchy** (`NameUsage.parent_id`); the flat,
denormalised higher-rank columns of the ColDP `Taxon` entity are deliberately
**not** used. Pro parte synonyms (one synonym under several accepted taxa) are
modelled as a single record with multiple accepted parents rather than by
duplication (see §5.5).

## 2. Goals and non-goals

### Goals
- Host multiple independent projects with per-project users and roles on one instance.
- Comfortable editing of References and NameUsages (collapsed name + usage), and the parent/child classification tree, at genus-to-Lepidoptera scale.
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
- Managing `accordingTo` on usages, or a separately-managed scrutinizer (see §5.4).

## 3. Technology stack

| Layer | Choice |
|---|---|
| Backend | **Java 25 (LTS), Spring Boot 4.1** (migrated from Java 21 / Boot 3.5 after 3.5 went EOL; build requires JDK 25 — `backend/.sdkmanrc` pins Liberica 25, use `sdk env`) |
| Persistence | **MyBatis** (`mybatis-spring-boot-starter`) — hand-written SQL |
| Database | **PostgreSQL 17** (single DB, shared schema) |
| Migrations | **Flyway** |
| Auth | **Spring Security**, ORCID OAuth2/OIDC (primary), local accounts (fallback) |
| Domain libs | GBIF **`name-parser` 4.2.0** (+ `name-parser-api` for types & `NameFormatter`), **`org.catalogueoflife:vocab:1.2.3-SNAPSHOT`** for the enums (pulls in `org.catalogueoflife:coldp`, i.e. the `ColdpTerm` vocabulary) — see [Appendix A](#appendix-a--name-parser-420-integration) & §5.0. ColDP archive `reader` module added in phase 2; writer in phase 3. |
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
  cross-project lookups and simple operations. The large `name_usage` table can
  be **partitioned by `project_id`** later if a single project grows very large;
  the schema is designed so this is a non-breaking change.
- **REST/JSON API** served by Spring Boot; the React SPA is the only client,
  consuming it via TanStack Query. The read API is deliberately shaped as a
  handful of **view-oriented endpoints** (e.g. `tree-children`, `usage-detail`,
  `search`) rather than generic per-table CRUD, so each screen fetches close to
  exactly what it needs and the hot tree path stays hand-tuned. GraphQL was
  considered and **deferred** (see §10); Spring makes it additive on the read
  side later if over/under-fetching becomes painful.
- **ORCID OAuth2** for login; a signed session/JWT authorises API calls.
- **Server-side tree traversal.** The tree shows **accepted usages only**
  (accepted / provisionally accepted); synonyms are never tree nodes — they load
  on the accepted taxon's detail page. Children are fetched lazily per node and
  paginated, so a 200k-taxon classification is never materialised at once in the
  browser or the API response.

## 5. Data model

**Name facts and taxonomic usage are collapsed into a single `name_usage`
entity** (§5.0, §5.4). The column lists in §5.1–§5.10 are indicative, not
exhaustive; the exact classes are the first implementation deliverable (§5.0)
and the full DDL lives in Flyway migrations.

### 5.0 Modeling approach: editor domain model vs ColDP raw

Two distinct models are kept separate, and **the wire format never dictates the
internal one**:

1. **Editor domain model — authoritative, editing-optimized (our own classes).**
   It *leans on* the ChecklistBank internal model's structure (Taxon/Synonym/
   BareName expressed as a `NameUsage` + `status`; the normalized Reference and
   Author entities) and *reuses* CLB/name-parser enums, but it **diverges where
   the editor is simpler**: name facts and taxonomic usage are **collapsed into
   one `name_usage` entity** (CLB keeps `Name` and `NameUsage` separate; here the
   relation is always 1:1 — see §5.4). It is **trimmed of all CLB machinery** —
   no `sectorKey`, `verbatimKey`, `datasetKey`, `namesIndexId`, no
   sectors/sources/decisions/matching, no DSID key scheme, no `VerbatimRecord` —
   and adds editor-only concerns instead (`project_id`, a surrogate id,
   `version`, links to audit/lock/issue). We **do not** import CLB's
   `Name`/`NameUsage`/`Taxon`/`Synonym` classes.
2. **ColDP raw — a boundary DTO only.** Faithful to the tabular format (TEXT ids,
   the merged-NameUsage option, all-string fields), used *solely* by the ColDP
   reader/writer at import/export and mapped to/from the domain model. It stays
   out of the core.

**Reuse boundaries.**
- *Reuse as dependencies:*
  - `org.gbif:name-parser-api` — `Rank`, `NomCode`, `NamePart`, `NameType`, plus
    `NameFormatter`.
  - **`org.catalogueoflife:vocab:1.2.3-SNAPSHOT`** — the CLB vocabulary enums
    (`TaxonomicStatus`, `NomStatus`, `NomRelType`, `TypeStatus`, `Gender`, `Sex`,
    `Environment`, `GeoTime`, `Gazetteer`, `DegreeOfEstablishment`,
    `EstablishmentMeans`, `DistributionStatus`, `EstimateType`, `Country`,
    `License`, `IdentifierScope`, …). It is its own module with only two
    dependencies, both of which we want anyway: `org.gbif:name-parser-api` and
    `org.catalogueoflife:coldp`.
  - **`org.catalogueoflife:coldp:1.2.3-SNAPSHOT`** (transitive via `vocab`) —
    essentially the **`ColdpTerm`** enumeration (the ColDP term/column
    vocabulary), key for column mapping on import/export. Not an archive
    reader/writer.
  - The ColDP archive **reader** is a separate module,
    **`org.catalogueoflife:reader`** (`life.catalogue.csv.ColdpReader`, also DwC-A
    and ACEF readers), added in **phase 2** for import. A ColDP **writer** for
    export is sourced in **phase 3** (see §8).
- *Define ourselves:* every entity class (Reference, Author, Name, NameUsage,
  supporting) as lean records tailored to editing.
- *Exclude entirely:* DSID keys, `VerbatimRecord`, sector/source/decision/matching,
  and the CLB `dao`/`webservice` layers.

**Identifiers.** *(revised per the 2026-07-08 model review — see the "Model
refinements" box below.)* Project-scoped entities (`reference`, `author`,
`name_usage`, `synonym_accepted`) use a **compound `(project_id, id)` primary
key** where `id` is a plain **`Integer`** allocated as a **per-project sequence
starting at 1** (via an `id_seq` counter table, atomic `INSERT … ON CONFLICT …
RETURNING`). All scalar foreign keys are therefore **compound** (`(project_id,
parent_id)` → `name_usage(project_id, id)`, etc.), so the **database enforces
same-project referential integrity** (the app-layer cross-project guard becomes
belt-and-suspenders). `project` and `app_user` keep a global `Integer` identity
PK. The original ColDP `ID` and `alternativeID[]` are preserved as attributes
for lossless round-trip; export maps the internal ids back to ColDP string IDs.

> **Model refinements (2026-07-08 review) — applied and green (`mvn verify`).**
> - **Identifiers:** `Long`→`Integer` everywhere; per-project `(project_id, id)`
>   compound keys + `id_seq` allocation (above).
> - **Project:** removed `slug`, `doi`, `issued`, `version` (kept `created_at` as
>   the project start). `nom_code` → `NomCode` enum; `license` → an enum
>   restricted to the two COL licenses only (`CC0-1.0`, `CC-BY-4.0`), else 400.
> - **NameUsage:** `status` → a custom `Status` enum **{accepted, synonym,
>   misapplied, unassessed}** (drops ColDP's *provisionally accepted* /
>   *ambiguous synonym*; `unassessed` replaces "bare name"; export maps
>   unassessed→bare name). `publishedInYear` → `int`. Typed enums:
>   `temporalRangeStart/end`→`GeoTime` (from `org.catalogueoflife:api`),
>   `nomStatus`→`NomStatus`, `gender`→`Gender`, `nameType`→`NameType`,
>   `notho`→`NamePart`, `environment`→`List<Environment>`.
> - **Releases (deferred):** a project's ColDP release date + version live on a
>   future **Release** entity (per-project: version, issued date, changelog,
>   released_by, + a link to the exported archive — *not* a DB data-snapshot;
>   the snapshot is the exported ColDP file), built alongside ColDP **export**
>   (phase 3). The working project is the editable head.

**Normalization stance: strings authoritative, relational normalization
optional.** For both authorship and names, the **denormalized string is the
authoritative, always-present field** (the full authorship string; the full
`scientific_name`), because it keeps the barrier to *getting data in* low and
keeps search trivial. The **name-parser gives a structured middle layer for
free** — atomized author tokens + year, and genus/epithet parts — which is
enough to facet, bulk-edit and soft-validate *without* any relational
normalization. True relational normalization is an **opt-in overlay**, never
forced:

- *Authors* — the authorship string on each name/reference is authoritative;
  linking to `author` person records (§5.3) is optional and additive (mirrors
  ColDP's `combinationAuthorship` + optional `combinationAuthorshipID`, and
  `author` + `authorID`). Small curated projects opt in; large-import projects
  stay on strings and harmonise later with faceting/reconciliation tooling (§8).
- *Names* — stored **denormalized** (full name + parsed parts), the botanist-
  liked, searchable, import-friendly form. We deliberately do **not** adopt the
  zoologist single-epithet-linked-to-parent *storage* model (it fights import
  and search and needs cached-name triggers). Its ergonomics are provided on top
  of the denormalized store instead: an epithet-only edit that recomposes the
  full name via `NameFormatter`, **soft validation** of genus/epithet vs the
  parent taxon (§6), and a **batch "rename genus / recombine subtree"** operation
  (§8, phase 4) for auditable rename propagation.

**First implementation deliverable.** The exact domain classes (fields, types,
enum bindings), the Flyway DDL, and the ColDP-raw ↔ domain mapping are settled
and reviewed **before** any service or UI work.

### 5.1 Project, users, membership

- **`project`** — global `Integer` `id`, ColDP metadata (`title`, `alias`,
  `description`, `license` [enum: CC0-1.0/CC-BY-4.0], geographic/taxonomic scope,
  plus a JSONB blob for the long tail of extensible YAML fields), **`nom_code`**
  (`NomCode` enum — the single nomenclatural code applied to *all* names in the
  project), and `created_at`/`updated_at`. *(Per the 2026-07-08 review: `slug`,
  `doi`, `issued`, `version` were removed — `issued`/`version` move to the future
  Release entity; projects are identified by `id`.)*
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
  `modified_by`. Structured fields + a formatted citation string. The `author` /
  `editor` **strings are authoritative**; optional `author_id[]` / `editor_id[]`
  links to `author` records (§5.3) are the normalization overlay (§5.0).

### 5.3 Author (optional normalization overlay)

- **`author`** — project-scoped normalised persons (the ColDP Author entity),
  **optional** and used only when a project chooses to normalize authorship (see
  §5.0). `id`, `project_id`, `alternative_id[]` (incl. `orcid:` / `wikidata:`
  scopes), `given`, `family`, `suffix`, `abbreviation_botany`, `affiliation`,
  `link`, `remarks`, plus **disambiguation fields** — `birth`, `death`,
  `birth_place`, `country` — which matter precisely for telling the many *Smith*s
  apart once you normalize. `modified` / `modified_by`.
- **Distinct from `app_user`.** Logged-in **editors** are `app_user` records
  (§5.1) and drive audit/`modifiedBy`; historic **taxonomic authors**
  (Linnaeus, Zeller) are `author` records. They are separate concerns — an
  editor needs no `author` record, and Linnaeus needs no account. A modern
  author who is also an editor *may* be linked (matching `orcid`), but that link
  is optional, not required.
- Names and references reference authors **only optionally**, via id links
  (§5.2, §5.4) that sit alongside the authoritative authorship strings.
  Populating `author` records and those links is a curation activity supported
  by the reconciliation/faceting tooling in §8, not a prerequisite for entering
  data.

### 5.4 NameUsage (collapsed name + taxonomic usage)

A single **`name_usage`** entity holds both the nomenclatural name facts and the
taxonomic usage; this table **is the classification**. (CLB keeps `Name` and
`NameUsage` separate; we collapse them because the relation is always 1:1 here.)

- Identity & classification:
  - `id`, `project_id`, `alternative_id[]`
  - `parent_id` → `name_usage` (self-ref, **accepted usages only**) — **builds
    the parent/child tree**; synonym→accepted links live in `synonym_accepted`
    (§5.5), not here
  - `basionym_id` → `name_usage` (self-ref, the original combination)
  - `ordinal` (sibling sort order)
- Nomenclatural (name) fields:
  - `scientific_name`, `authorship`, `rank`
  - atomized parts: `uninomial`, `genus`, `infrageneric_epithet`,
    `specific_epithet`, `infraspecific_epithet`, `cultivar_epithet`, `notho`
  - atomized authorship (**strings authoritative**, parser-derived):
    `combination_authorship`, `combination_ex_authorship`,
    `combination_authorship_year`, `basionym_authorship`,
    `basionym_ex_authorship`, `basionym_authorship_year`, `sanctioning_author`
  - optional author-link overlay (§5.0): `combination_authorship_id[]`,
    `basionym_authorship_id[]` → `author` records, used only when normalizing
  - `nom_status` (nomenclatural status), `published_in_reference_id`,
    `published_in_year`, `published_in_page`, `published_in_page_link` (URL to
    the exact page where the protologue starts, e.g. a BHL page — ColDP
    `publishedInPageLink`), `gender`, `etymology`
  - **No `code` column** — derived from `project.nom_code`.
- Taxonomic (usage) fields:
  - `status` — the custom **`Status`** enum ∈ {**accepted, synonym, misapplied,
    unassessed**} *(2026-07-08 review; unassessed replaces "bare name";
    provisional/ambiguous dropped)*
  - `name_phrase` (free-text qualifier, e.g. `auct. non …`, `sensu lato`)
  - `reference_id[]` (taxonomic references supporting the usage)
  - `extinct`, `environment[]` (`List<Environment>`), `temporal_range_start/end`
    (`GeoTime`)
  - **No `according_to_reference_id` / `accordingToPage`** (dropped per decision).
  - **No scrutinizer fields** — the scrutinizer is derived from the audit log
    (last editor + `modified` timestamp) whenever it needs to be shown or
    exported.
- Common: `link`, `remarks`, `version`, `modified` / `modified_by`.
- Parent semantics: `parent_id` links **accepted** usages into the classification
  tree (accepted → parent accepted). Synonyms, ambiguous synonyms and misapplied
  names do **not** use `parent_id`; their link(s) to accepted taxa live in the
  `synonym_accepted` table (§5.5). Bare names have neither.
- **The tree shows accepted usages only.** Synonyms are not tree nodes; they
  surface on their accepted taxon's detail page, loaded via `synonym_accepted`
  (§5.5). The tree "children of X" query is therefore a clean `parent_id = X`
  over accepted usages, while the taxon detail view loads that taxon's synonyms
  separately.
- On entry the **GBIF name-parser** atomizes `scientific_name` + `authorship`
  into the parts above; the user can also edit atomized fields directly. The
  4.2.0 parser API differs substantially from older versions — the
  exact contract we bind to is in
  [Appendix A](#appendix-a--name-parser-420-integration). Display
  strings come from GBIF **`NameFormatter`** using the project code.
- **Protologue page link (`published_in_page_link`) + BHL tooling.** The field
  holds a deep link to the exact page where the name's protologue starts. A bit
  of **BHL integration** helps populate and use it: paste/resolve a
  [Biodiversity Heritage Library](https://www.biodiversitylibrary.org) page URL
  (normalising to a stable `page/{id}` deep link), and later preview the page
  image inline. Minimal paste/validate lands with the field; richer BHL
  search/preview is phase 5 (§8).
- **Subtree operations** (move subtree, descendant counts, subtree locking):
  `name_usage` carries a **Postgres `ltree` materialized path** (or a closure
  table) maintained on insert/move, so subtree queries and locks are cheap and
  don't require deep recursive scans on the hot path.

### 5.5 Synonymy — the `synonym_accepted` relation

All synonym→accepted links live in one relation table, keeping `parent_id`
purely for the accepted classification tree:

- **`synonym_accepted`** — `(synonym_usage_id, accepted_usage_id, ordinal?)`.
  Each row links a non-accepted usage (synonym / ambiguous synonym / misapplied)
  to an accepted taxon it belongs under. `accepted_usage_id` must reference an
  accepted usage. Optional `ordinal` gives a stable display/export order.
- A normal synonym has **exactly one** row. A **pro parte** synonym — one name
  used as a synonym under several accepted taxa — has **several** rows: it stays
  a single `name_usage` record and gains multiple accepted links, so we never
  duplicate nomenclatural facts (the traditional `ambiguous synonym`/duplication
  workaround is unnecessary). `status` stays `synonym` (or `ambiguous synonym`
  only when placement is genuinely uncertain).
- A taxon detail page lists its synonyms with a single join
  `synonym_accepted.accepted_usage_id = X`; a pro parte synonym therefore
  appears on **each** of its accepted taxa's pages. Synonyms are never tree
  nodes (§5.4), so this relation feeds only detail pages, not the tree.
- **Why the unified table** (vs. a primary `parent_id` + overflow table):
  because synonyms are not tree nodes, reserving `parent_id` for accepted edges
  costs nothing and makes both queries trivial — the tree is a pure
  `parent_id` scan and synonymy is a pure `synonym_accepted` join, with no
  per-node union.
- **Round-trip.** Export to the normalized ColDP files emits **one `Name` row +
  N `Synonym` rows** (one per accepted link, all sharing that `Name.ID`) — the
  representation ColDP prescribes, with no field drift. Import re-collapses
  multiple `Synonym` rows sharing a `nameID` (or duplicate merged-NameUsage rows
  for one name) back into a single record with multiple `synonym_accepted` rows
  — a natural fit for the phase-2 dedup work.

### 5.6 Supporting entities

Schema present from the start; **editing UX phased in later** (phase 5):
`name_relation`, `type_material`, `distribution`, `vernacular_name`,
`taxon_property`, `species_estimate`, `species_interaction`, `media`. All
project-scoped with `modified` /
`modified_by`. (ColDP's `TaxonConceptRelation` is **not** modelled — dropped from
the schema.) Since name and usage are collapsed, ColDP's `nameID` and
`taxonID` foreign keys both resolve to a single `name_usage` id here (e.g.
`type_material` and `name_relation` reference name usages directly).

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
  a `reference` re-checks the usages that cite it; reparenting re-checks the
  moved node and its old/new parents). A **full project re-validation** can also
  be run on demand (and after import in phase 2).
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
  rank inconsistent with parent rank; **genus token inconsistent with the parent
  genus**, and an **infraspecific epithet's species-part inconsistent with the
  parent species** (the consistency the zoologist normalized model would enforce
  structurally — §5.0); synonym linked (via `synonym_accepted`) to a
  non-accepted usage; missing `published_in` reference; duplicate scientific
  name + authorship within a project; dangling reference/parent/synonym-accepted
  pointers. The rule catalogue grows over time.

## 7. Phase 1 scope (first working version)

The buildable slice:

1. **Multi-project + auth**: ORCID OAuth2 login (local-account fallback),
   per-project roles (owner/editor/reviewer/viewer), project switcher.
2. **Project ColDP metadata** editing, including selecting the single
   `nom_code`.
3. **References**: CRUD + fuzzy search.
4. **NameUsages** (collapsed name + usage): CRUD + fuzzy search, GBIF
   name-parser auto-atomization on entry, `NameFormatter` rendering using the
   project code.
5. **Classification tree**: create / rename / move / change-status, lazy-loaded
   and virtualized, sibling ordering; synonyms attach to their accepted usage,
   including pro parte synonyms with multiple accepted parents (§5.5).
6. **Soft locks** on records/subtrees + optimistic-locking conflict handling.
7. **Audit log** with a per-project recent-changes view and single-record
   revert.
8. **Async validation infrastructure** (§6) + the starter rule set, with soft
   issue badges in the tree/forms and a per-project problems view.

Explicitly **out** of phase 1: ColDP import, ColDP export, batch/bulk editing,
supporting-entity editing UX. (The validation *engine* is in; the rule
*catalogue* keeps growing in later phases.)

## 8. Phasing roadmap (after v1)

- **Phase 2 — ColDP import.** Read the archive with `org.catalogueoflife:reader`
  (`ColdpReader`); choose which entities to import; **dedup/merge** against
  existing records. Matching keys: shared identifiers first, then parsed name +
  author/year (via name-parser) for names, DOI/title for references. Present a
  per-record **merge / skip / replace** decision UI, with a bulk "apply to all
  similar" affordance. (The `reader` module also parses DwC-A and ACEF, a
  possible future import source.)
- **Phase 3 — ColDP export.** Write a valid ColDP ZIP (normalized entity files +
  `metadata.yaml`). The `reader` module is read-only, so export uses a writer
  sourced this phase — a TSV/ZIP layer keyed off the `ColdpTerm` vocabulary, or a
  backend ColDP writer if one is available. Round-trips `modified` /
  `modified_by` and derives scrutinizer from the audit log.
- **Phase 4 — Batch editing & harmonization.** Multi-select in tree/search →
  bulk change rank/status/parent, move subtrees, find-and-replace authorship,
  bulk delete; every operation captured in the audit log and revertible.
  Includes:
  - a **batch "rename genus / recombine subtree"** operation that rewrites the
    genus token + full name across a subtree's descendants (the auditable
    stand-in for the zoologist normalized model's rename propagation — §5.0);
  - **OpenRefine-style faceting and bulk edit** over the authoritative authorship
    and name *strings* (facet on parser-derived author tokens, years, genus,
    epithets; cluster near-duplicates; bulk-apply fixes);
  - **author reconciliation** — promote a cluster of matching author strings into
    a shared `author` record and link the names/references to it, optionally
    reconciled against **Wikidata** (strong historic-author coverage) and ORCID.
    This is the opt-in on-ramp from string authorship to the normalized overlay
    (§5.0, §5.3).
- **Phase 5 — Supporting-entity editing UX** (type material, distributions,
  vernacular names, relations, etc.) + reference/nomenclature enrichment:
  DOI/CrossRef lookup, BibTeX/CSL-JSON import, and **BHL integration** (search
  the Biodiversity Heritage Library, resolve/normalise protologue page deep
  links for `published_in_page_link`, and preview the page image inline).

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

- Standalone app; reuse CoL/GBIF Java libraries: `name-parser` / `NameFormatter`, and `org.catalogueoflife:vocab` (enums) + transitively `org.catalogueoflife:coldp` (terms). ColDP archive reader/writer sourced in phases 2–3.
- Async multi-user with optimistic locking **and** soft (advisory) subtree/record locks.
- Phase 1 = foundation + core editing; import/export/batch deferred.
- ORCID login primary, local accounts as fallback; ORCID doubles as the authorship identifier.
- Backend: Spring Boot + MyBatis + Postgres + Flyway + Spring Security.
- Postgres-only search (`pg_trgm`), no Elasticsearch.
- Name and taxonomic usage **collapsed into one `name_usage` entity** (1:1 here); parent/child classification only (accepted usages), flat higher ranks ignored. Synonyms are not tree nodes and appear on taxon detail pages only. All synonym→accepted links (including pro parte, multiple accepted taxa) live in a single **`synonym_accepted`** relation table, keeping `parent_id` purely for the accepted tree; no record duplication. Export fans out to one Name + N Synonym rows, import re-collapses.
- **Strings authoritative, relational normalization optional** (§5.0). The authorship string and the full `scientific_name` are the always-present authoritative fields; name-parser's atomized tokens/parts give a free structured layer for faceting/validation. Relational normalization is an opt-in overlay, never forced.
- **`author` entity is optional and distinct from `app_user`** (§5.3). Editors are `app_user` (drive audit/`modifiedBy`); historic taxonomic authors are optional `author` records with disambiguation fields (birth/death/place). Name/reference→author links are optional and sit beside the authoritative strings (mirrors ColDP's string + optional ID design).
- **No zoologist single-epithet storage model.** Names stay denormalized (full name + parsed parts); the normalized model's benefits come via soft validation (genus/epithet vs parent) and a batch rename/recombine op (§5.0), not a second schema.
- **OpenRefine-style faceting + author reconciliation** (promote string clusters to `author` records, reconcile against Wikidata/ORCID) is later-phase (phase 4) harmonization tooling, not phase-1 critical path.
- Two-layer model (§5.0): an authoritative editor domain model (our own lean classes, leaning on CLB's structure, reusing CLB/name-parser enums, trimmed of CLB machinery) plus a ColDP-raw DTO used only at the import/export boundary. We do not import CLB's entity classes. Surrogate project-scoped PKs, with original ColDP IDs preserved for round-trip. Exact classes/DDL/mapping are the first implementation deliverable.
- `ltree` materialized path for subtree operations.
- Single `nom_code` per project (drives NameFormatter and code-specific behaviour); no per-name code.
- `accordingTo` dropped from usages; scrutinizer derived from the audit log, not managed separately.
- Async, non-blocking validation: rules run out-of-band and attach advisory `issue` findings; bad data is allowed and only softly flagged. Engine + starter rules land in phase 1.
- Name parsing/formatting binds to GBIF **name-parser 4.2.0**, whose API differs substantially from older releases (see Appendix A). Only name-level combination/basionym authorship is used; per-epithet `genericAuthorship`/`specificAuthorship` are ignored.
- **REST/JSON, not GraphQL**, for phase 1. GraphQL's flexible nested reads suit the relational data, but its costs (N+1/DataLoader complexity on the 200k-node tree, loss of HTTP/Varnish caching, divergence from the REST CLB/portal ecosystem) outweigh the gain for a single first-party client. The API is shaped as view-oriented read endpoints; GraphQL can be layered onto reads later if needed. Middle path if end-to-end typing is wanted: OpenAPI + TS codegen.

## Appendix A — name-parser 4.2.0 integration

We depend on the **4.2.0** GBIF name-parser, whose API differs
substantially from the 3.x / earlier 4.x releases. The implementation and any
future work must bind to *this* contract, verified against the local checkout at
`~/code/gbif/name-parser`. Do **not** assume the older `NameParserGBIF`
singleton / two-arg `parse` shapes.

**Coordinates.** `org.gbif:name-parser:4.2.0` (impl) and
`org.gbif:name-parser-api:4.2.0` (types + `NameFormatter` + `RankUtils`) — the
**released 4.2.0** version (no longer a SNAPSHOT), resolvable from Maven Central /
the GBIF repository.

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

Mapping `ParsedName` → the name fields of `name_usage`:

| `name_usage` name field | Source |
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
