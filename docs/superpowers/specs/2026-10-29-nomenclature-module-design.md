# Reuse the CLB nomenclature module: homotypy, author matching and name relation cleanup

*Design spec — 2026-10-29*
**Status:** Implemented 2026-10-30; see `docs/superpowers/plans/2026-10-30-nomenclature-module.md`.

## Context

Blixa reimplemented parts of ChecklistBank's nomenclatural logic because the originals lived in
CLB's `dao`/`core` modules, which drag Elasticsearch, Chronicle, MyBatis, Postgres and image
libraries along. CatalogueOfLife/backend#1682 moved the pure logic into a new module,
`org.catalogueoflife:nomenclature` (depends on CLB `api` and `parser` only). It is on GBIF Nexus as
1.7.0-SNAPSHOT, the CLB version Blixa already uses (commit f488453). The module's own design record
is `backend/docs/2026-10-29-nomenclature-module.md` in the CLB repo.

The module has what Blixa copied or still lacks:

- `basgroup.BasionymSorter` + `HomotypicGroup` + `HomotypicRules`: CLB's homotypic grouping.
- `matching.authorship.AuthorComparator`: author comparison with abbreviations, initials, alias
  lookup and year tolerance, returning `Equality` (EQUAL / DIFFERENT / UNKNOWN).
- `basgroup.NameRelationRules` + `NameRelationJudge<R>`: the name relation cleanup of
  CatalogueOfLife/data#1815. The judge groups relations by pair of names, collapses exact
  duplicates and judges each pair. It returns verdicts with `remove` (the relation is wrong or
  redundant) or flag-only (something conflicts but nothing tells which relation is right).

All of them work on the CLB api model (`life.catalogue.api.model.Name`, `FormattableName`), not on
Blixa's `NameUsage`.

## Goals

1. Blixa depends on `nomenclature` and drops its own copies:
   - `HomotypyDetector` uses `BasionymSorter` instead of clustering on exactly equal normalized
     author strings and years.
   - `HomotypicRelations` gives way to `NomRelType.isHomotypic`.
   - `NameMatcher` compares authors with `AuthorComparator`.
2. Editors can clean up the name relations of a subtree or a whole project. Blixa proposes, the
   editor reviews and applies, as with homotypic conflicts. Nothing is deleted without the editor
   ticking it.

## Non-goals

- No automatic or background deletion of relations, and no cleanup on import. The ColDP import
  keeps relations exactly as delivered.
- No persistent "dismiss" state for verdicts the editor leaves unticked; they show up again on the
  next scan, like skipped homotypic conflicts.
- No new validation rule for relation conflicts in this round; the review page is the only surface.
- No port of `HomotypicConsolidator`'s DB logic or of CLB's sector priorities.
- No change to the consolidation mutation (`ConsolidationService.consolidate`) or the synonymy read
  model beyond the shared type list.

## Anti-goals

- A relation disappearing without an audit `DELETE` change, or without the editor having ticked it.
- Merge (`NameMatcher`) silently merging names whose authors genuinely differ, e.g. two different
  authors of the same epithet. `AuthorComparator` may make more names MATCHED, but only where the
  authors are the same people written differently.
- Name ids changing. Relations are only deleted, never recreated.

## Constraints

- Java 25, Spring Boot 4.1, MyBatis annotation mappers, Flyway (no migration expected here).
- CLB jars bring Jackson 2 (`com.fasterxml`) next to Blixa's Jackson 3; this already works.
- **Name parser versions:** Blixa pins `name-parser` 5.4.0-SNAPSHOT and `name-parser-rust`
  0.3.1-SNAPSHOT, while CLB 1.7.0-SNAPSHOT, and with it the module, is built on 6.0.0-SNAPSHOT /
  0.4.1-SNAPSHOT. Blixa moves to CLB's versions as the first step (Part 0 below), so Blixa and
  every CLB jar it loads run on the same parser.
- Exclude `junit-vintage-engine` and `logback-classic` from the new dependency, as for `reader`.
- Stored `name_relation.type` is not canonical: import writes the raw ColDP cell. Today
  `HomotypicRelations.normalize` lowercases it and maps `_`/`-` to spaces before comparing. The UI
  and the homotypy apply path write the lowercase-with-spaces form ("spelling correction").

## Design

### Part 0: name parser bump

- Set `name-parser.version` to 6.0.0-SNAPSHOT and `name-parser-rust.version` to 0.4.1-SNAPSHOT in
  `backend/pom.xml`, the versions CLB 1.7.0-SNAPSHOT uses. The comment there already says to bump
  them together.
- Fix what the 6.0 API breaks in Blixa's own code (`parse/NameParserService`,
  `parse/ParsedNameMapping`, anything else on `org.gbif.nameparser.*`), keeping behaviour. Where 6.0
  parses a name differently, update the test expectation only if the new result is right; note
  each such change in the commit message.
- Clean build (`mvn -o clean verify`, stale classes hid API breaks during the CLB bump) and the full
  suite green before Part 1 starts.

### Shared: `NameUsage` → CLB `Name`

A new pure mapper, e.g. `org.catalogueoflife.editor.nomenclature.ClbNames`, turns a `NameUsage`
into a `life.catalogue.api.model.Name` for the module's rules:

- id: `String.valueOf(usage.getId())`. In Blixa a usage *is* its name, so usage ids stand in for
  name ids throughout.
- scientificName, authorship, the atomized parts (uninomial, genus, infrageneric, specific,
  infraspecific, cultivar epithet), notho, nameType, nomStatus, publishedInYear.
- rank: parsed the way `NameParserService` already does (`Rank.valueOf` of the normalized string,
  `UNRANKED` on failure). Reuse that code; don't copy it.
- code: the project's nomenclatural code.
- combination and basionym `Authorship`: authors and ex authors split on `|` (the
  storage form, see `ParsedNameMapping.join`). The year has the brackets that
  `ParsedNameMapping.year` adds for a bracketed year stripped.

Plus a small helper turning a stored relation type string into `NomRelType`:
normalize (trim, lowercase, `_`/`-` → space), then upper-case with `_` and `NomRelType.valueOf`.
Unknown → `null`, and callers skip that relation.

### Part 1a: `HomotypicRelations` → `NomRelType.isHomotypic`

- Delete `HomotypicRelations`. Its callers use the relation type helper plus `NomRelType.isHomotypic()`,
  and keep the existing-relation key normalization in `HomotypyDetector.rel` working.
- `SynonymyMapper.homotypicClosure` hardcodes the six homotypic types in SQL. Pass the list in as a
  parameter derived from `NomRelType` (values with `isHomotypic()`, in the lowercase-with-spaces form
  the SQL compares against), so the enum is the only source. The six types are the same today:
  SPELLING_CORRECTION, BASIONYM, BASED_ON, REPLACEMENT_NAME, SUPERFLUOUS, HOMOTYPIC.

### Part 1b: `NameMatcher` → `AuthorComparator`

- `authorCompatible` compares the two usages' authorships, built with the mapper above, using one
  shared `AuthorComparator` (constructed with `AuthorshipNormalizer.INSTANCE`). Compatible means the
  result is not `DIFFERENT`. A blank authorship on either side stays compatible, as today. The
  method changes from two Strings to two `NameUsage`s.
- **Fallback for unparsed names** *(my call)*: "blank" means the raw `authorship` column. If either
  usage has a raw authorship but no atomized combination or basionym authorship (unparsable or
  informal names), compare the raw strings with today's exact normalized-string rule instead of
  `AuthorComparator`. Otherwise two same-named unparsed usages with different authors would turn
  from POSSIBLE_HOMONYM into MATCHED. Test both branches.
- `canonicalKey` and the fuzzy fallback are unchanged.
- Update `NameMatcherTest` to the new semantics: abbreviation vs full name of the same author
  ("L." vs "Linnaeus") becomes compatible; different authors stay incompatible. Builder's call
  which `AuthorComparator` method fits best (`compare(Authorship, Authorship)` per part, or the
  `ScientificName` overload); pick the one CLB's own matching uses.
- Check the merge ITs that document the old exact-string behaviour (`MergeApplyModesIT`,
  `MergeApplyNamesIT`) and update their fixtures/comments only where the new comparator
  legitimately changes the category.

### Part 1c: `HomotypyDetector` → `BasionymSorter`

Keep `HomotypyDetector`'s output (`detect(...)` and `group(...)` → `HomotypyProposal` of
`ProposedGroup`s), so the DTOs and the frontend stay as they are. Only the existing-relations
argument changes, see below. Inside:

- Map candidates with the shared mapper, and bucket them by normalized terminal epithet
  (`SciNameNormalizer.normalizeEpithet`), as `HomotypicConsolidator` does. Skip names without a usable
  terminal epithet and supraspecific names, as the consolidator does, and keep the existing caller
  pre-filters.
- The detector needs the existing relations among the candidates, not just the exact-key set.
  Replace `existingKeys` with a small in-memory structure built by the callers from
  `name_relation`. It holds, per usage id, the usages it is homotypically related to, either
  direction (any `NomRelType.isHomotypic` type), the usages it is a later homonym of / has as later
  homonym, and the usages it is a spelling correction of. It also answers the old
  `exists(from, to, type)` for `ProposedRelation.exists`. `HomotypyService` and
  `ConsolidationService` build it; for the subtree scan, from the relations among the candidates.
- Per bucket, `BasionymSorter.groupBasionyms(code, epithet, names, resolver, linked, evidence)`.
  **Both get real data in v1** *(my call; with a constant priority and no `linked`, `BasionymSorter`
  always splits original names in different genera, which would drop cross-genus groups that Blixa
  finds today)*. Mirror `HomotypicConsolidator.linked`:
  - `linked(u1, u2)` is true if one is a synonym of the other, or both are synonyms of the same
    accepted name, or the existing relations relate them homotypically.
  - `evidence.isVariant(u)` is true if an existing spelling correction relation marks u as the
    misspelling.
  - `evidence.penalty(u)` ranks an accepted name before a synonym, and an available `nomStatus`
    before a non-available one.
  - The priority function stays constant.
- Map each `HomotypicGroup` to a `ProposedGroup`, with the relation types and **directions**
  `HomotypicConsolidator` creates, following ColDP:
  - with a basionym: each recombination → `basionym` relation from the recombination to the
    basionym. Each basionym variation → `spelling correction` if
    `HomotypicRules.isOrthographicVariant`, else `homotypic`. The relation runs in the direction
    `HomotypicConsolidator.relateVariation` writes it: a spelling correction runs original → variant,
    a homotypic relation runs variant → original.
    Duplicates are skipped (`HomotypicRules.isDuplicate`).
  - without a basionym: every further recombination → `homotypic` to the first one, skipping
    duplicates.
  - a based-on name → `based on` from the primary name. Its variations are handled as above.
- **Don't propose relations the cleanup would flag** *(my call, mirroring
  `HomotypicConsolidator.createRelationIfNotExisting`)*. Skip a proposed relation when the existing
  relations already link both names homotypically, directly or through a single other name, or
  relate them as later homonyms. So Part 1 never creates what Part 2 then reports as redundant or
  conflicting.
- Groups without any relation need not be returned. Neither `ConsolidationService` nor the frontend
  relies on singleton groups.
- `ProposedRelation.exists` is answered by the existing-relations structure.
- Rewrite `HomotypyDetectorTest` against the new grouping. The cases that matter:
  - an abbreviated author ("L." vs "Linnaeus") now groups;
  - a year off by one within `AuthorComparator`'s tolerance groups;
  - different authors don't group;
  - unbracketed originals in two genera group when a synonym or relation links them, and stay
    apart when nothing does;
  - a known misspelling never becomes the basionym;
  - no relation is proposed between names already linked or related as later homonyms;
  - a misapplied name is excluded;
  - existing relations are marked `exists`. `ConsolidationApiIT` and `HomotypyApiIT` must still pass, or be adjusted where
  the better grouping legitimately changes their expectations.

### Part 2: Name relation cleanup

**Backend.** A new `NameRelationCleanupService` + controller in `name/homotypy` (next to
consolidation).

- `GET /api/projects/{pid}/name-relations/conflicts?rootId=` (rootId optional; without it, the whole
  project), any project role:
  1. Load the candidate relations. Whole project: `NameRelationMapper.findByProject`. With rootId:
     the relations where at least one of the two usages is in the subtree of rootId, synonyms
     included. Reuse the subtree enumeration that consolidation/validation use (`findSubtreeIds` or
     equivalent).
  2. Skip relations without a related usage or with an unknown type.
  3. Load the involved usages, map them to `Name`, and run
     `new NameRelationJudge<NameRelationResponse>(r -> new Rel(type, usageId, relatedUsageId)).judge(relations, names::get)`.
  4. Return one entry per `PairResult` with: both names (id, formatted label, status), the
     duplicates, and the verdicts. Each relation is shown as id, version, type, from → to, reference
     and remarks, plus for a verdict its `issue` and `remove`. One relation can carry several
     verdicts. Show it once, with all its issues, and with `remove` if any verdict removes it.
- `POST /api/projects/{pid}/name-relations/cleanup` (editor/owner), body
  `{ relations: [{id, version}] }`, `@Transactional`:
  - Every listed relation must still exist with that version, or the whole request fails with 409
    and nothing is deleted.
  - Each deletion goes through the same path as `NameRelationService.delete`: audit `DELETE` with
    the before image, plus `ValidationEvent.forUsage` for the owning usage. Factor the shared part
    out rather than duplicating it. Deleting needs a version check that the single delete lacks.
  - Respect soft locks the same way `NameRelationService.delete` does today, and no more.
  - Returns the number deleted.
- Issue labels: send the CLB `Issue` enum name, and let the frontend show a short explanation per
  issue (NAME_RELATION_CONFLICT, NAME_RELATION_REDUNDANT, SPELLING_CORRECTION_IMPLAUSIBLE, plus a
  label for exact duplicates).

**Frontend.**

- Entry points:
  - "Clean name relations" in `NameActionMenu` next to "Find homotypic conflicts" →
    `/projects/:pid/relation-conflicts/:rootId`.
  - A project-wide entry → `/projects/:pid/relation-conflicts`. Put it wherever the project
    already offers its project-wide actions (e.g. next to "Revalidate"); builder's call.
- `RelationConflictsPage`, modelled on `HomotypicConflictsPage`: a full page, one card per pair of
  names showing both names and their relations.
  - Ticked by default: relations with `remove`, and exact duplicates.
  - **Duplicates keep their content** *(my call)*. `NameRelationJudge` collapses on (type, from, to)
    only and keeps the first. Blixa's create path doesn't dedup, so a later duplicate may be the
    only row with a reference, page or remarks.
    - The service picks the row to keep itself: the one with the most of reference, page and
      remarks filled, then the lowest id.
    - A duplicate is ticked by default only if it carries nothing the kept row lacks. Otherwise it
      is listed as a duplicate but unticked.
  - Flag-only verdicts are shown with their issue but not ticked. The editor may still tick them.
  - Relations of the pair without a verdict are listed for context, without a checkbox.
- One "Delete selected" action for the whole page. Confirm first (count of relations), then
  delete, then refetch. On 409, show the standard conflict message and refetch.
- An empty state ("No conflicting name relations") and a help page under `src/help/pages`. Link it
  from the page header like `HomotypicConflictsPage` does, and from the glossary/validation pages
  where relations are explained.
- Only editors/owners see the checkboxes and the delete action; viewers see the list.

## Testing

TDD per part; RED first.

- Unit: the `NameUsage` → `Name` mapper (authors with `|`, ex authors, bracketed year, unknown
  rank), the type string helper, `HomotypyDetectorTest` (new semantics), `NameMatcherTest` (new
  semantics).
- IT (Testcontainers): the cleanup service on a small fixture covering a WoRMS-style basionym +
  spelling correction pair, a later homonym with a homotypic relation, a relation given both ways,
  an exact duplicate, and a clean pair that must not appear. Cover subtree scoping (a pair with one
  name outside the subtree is included, a pair with both outside is not). Cover apply: audit rows
  written, a stale version → 409 with nothing deleted, a viewer → 403.
- Frontend: vitest + MSW for `RelationConflictsPage`: default ticks, flag-only unticked, delete
  sends the ticked ids with versions, and the 409 path.
- Full `mvn verify` and `npm run build` + `npm test` before pushing.

## Delivery

At least three commits, Part 0 (parser bump), Part 1 and Part 2, each green on its own, pushed to
`main`. Update `backlog.md` and this spec's
status when done.

## Left to the builder

The package and class names, which `AuthorComparator` method to use, the shape of the
existing-relations structure, the project-wide entry point's placement, and the exact copy of
labels and help text.
