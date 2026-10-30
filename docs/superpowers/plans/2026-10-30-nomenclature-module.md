# Nomenclature Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move Blixa onto CLB's `nomenclature` module (homotypic grouping, author comparison, relation types) and let editors review and delete conflicting name relations found by `NameRelationJudge`.

**Architecture:** A pure mapper turns Blixa's `NameUsage` into the CLB api `Name` the module works on, with usage ids as name ids. Part 1 swaps Blixa's hand-rolled `HomotypyDetector`, `HomotypicRelations` and `NameMatcher.authorCompatible` for module code, keeping their outputs. Part 2 adds a scan endpoint (judge relations of a subtree or project) and an apply endpoint (delete the ticked relations with version check and audit), plus a review page.

**Tech Stack:** Java 25, Spring Boot 4.1, MyBatis annotation mappers, Testcontainers ITs; React 18 + TypeScript + Mantine + TanStack Query, vitest + MSW.

**Spec:** `docs/superpowers/specs/2026-10-29-nomenclature-module-design.md`

## Global Constraints

- Work on `main`; every commit green on its own. Commit messages never mention Claude (no trailers, no session links).
- `name-parser.version` 6.0.0-SNAPSHOT, `name-parser-rust.version` 0.4.1-SNAPSHOT, CLB artifacts 1.7.0-SNAPSHOT.
- `org.catalogueoflife:nomenclature:1.7.0-SNAPSHOT` with exclusions `junit-vintage-engine` and `logback-classic`, as for `reader`.
- Backend commands need JDK 25 (`sdk env` in `backend/`). Unit test: `mvn -o test -Dtest=<FQN>`. IT: `mvn -o test-compile failsafe:integration-test failsafe:verify -Dit.test=<FQN>` (Docker via `orb start`).
- After any dependency change run `mvn -o clean ...`; stale classes hid API breaks before.
- Relation type strings compare after normalization: trim, lowercase, `_`/`-` → space. UI writes lowercase-with-spaces ("spelling correction").
- No relation is deleted unless an editor ticked it; every deletion writes an audit `DELETE` with the before image and publishes `ValidationEvent.forUsage(projectId, usageId)`.
- Name ids are never changed; relations are only deleted.

## Review Focus

1. **Dangling relations**: a relation whose `related_usage_id` is null, points at a missing usage, or relates a usage to itself must be skipped by the scan, not crash it (pinned in Task 6).
2. **Raw ColDP type spellings**: imported types like `BASIONYM`, `spelling-correction`, `Spelling_Correction` must map to `NomRelType`; an unknown type (`"foo"`) is skipped, never judged (pinned in Task 2 and Task 6).
3. **Unparsed names**: usages with no rank, no atomized parts or a blank authorship must map to `Name` without NPE, and take part in grouping, matching and judging (pinned in Task 2, Task 4, Task 6).
4. **Whole-project scale**: the project-wide scan must bulk-load relations and usages (one query each), not query per relation (pinned in Task 6).
5. **Editor ticks a flag-only verdict or a content-carrying duplicate**: apply must delete exactly what was sent, nothing more (pinned in Task 7 and Task 8).

---

### Task 1: Name parser 6.0 bump (Part 0)

**Files:**
- Modify: `backend/pom.xml` (`name-parser.version`, `name-parser-rust.version`)
- Modify as the compiler demands: `backend/src/main/java/org/catalogueoflife/editor/parse/NameParserService.java`, `parse/ParsedNameMapping.java`, any other `org.gbif.nameparser.*` user
- Test: existing suites (`parse/*Test`, all ITs)

**Interfaces:**
- Produces: Blixa compiling and passing on name-parser 6.0.0-SNAPSHOT / rust 0.4.1-SNAPSHOT.

- [ ] **Step 1:** Set the two versions in `backend/pom.xml`; keep the comment that says to bump them together.
- [ ] **Step 2:** Run `mvn -o clean test-compile`. Expected: compile errors only where the 6.0 API changed. Fix each keeping behaviour.
- [ ] **Step 3:** Run `mvn -o test`. For any changed parse result, keep the new expectation only if the new parse is right; otherwise fix the mapping. Write down each changed expectation.
- [ ] **Step 4:** Run `mvn -o verify`. Expected: BUILD SUCCESS, 0 failures.
- [ ] **Step 5: Commit** — `build(parser): move to name-parser 6.0 / rust 0.4.1, the versions CLB 1.7 uses` with the list of changed parse expectations in the body (or "no parse results changed").

### Task 2: nomenclature dependency, `NameUsage` → `Name` mapper, relation type helper

**Files:**
- Modify: `backend/pom.xml` (add `org.catalogueoflife:nomenclature`)
- Modify: `backend/src/main/java/org/catalogueoflife/editor/parse/NameParserService.java` (make `parseRank` public static)
- Create: `backend/src/main/java/org/catalogueoflife/editor/nomenclature/ClbNames.java`
- Create: `backend/src/main/java/org/catalogueoflife/editor/nomenclature/RelTypes.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/nomenclature/ClbNamesTest.java`, `RelTypesTest.java`

**Interfaces:**
- Produces:
  - `ClbNames.toName(NameUsage u, @Nullable NomCode code) -> life.catalogue.api.model.Name` (id = `String.valueOf(u.getId())`)
  - `ClbNames.authorship(@Nullable String authors, @Nullable String exAuthors, @Nullable String year) -> org.gbif.nameparser.api.Authorship` (null/blank → empty Authorship)
  - `RelTypes.parse(@Nullable String stored) -> @Nullable NomRelType`
  - `RelTypes.isHomotypic(@Nullable String stored) -> boolean`
  - `RelTypes.label(NomRelType t) -> String` (lowercase with spaces, e.g. `"spelling correction"`)
  - `RelTypes.HOMOTYPIC_LABELS -> List<String>` (labels of `NomRelType.HOMOTYPIC_RELATIONS`)
  - `NameParserService.parseRank(@Nullable String) -> Rank` (now public static)

- [ ] **Step 1: Write the failing tests**

`ClbNamesTest`:
- `mapsAtomizedPartsRankCodeAndId`: usage id 7, rank `"species"`, genus `Aus`, specificEpithet `bus`, nomStatus, publishedInYear 1758, code ZOOLOGICAL → `getId()=="7"`, `getRank()==SPECIES`, `getGenus()=="Aus"`, `getCode()==ZOOLOGICAL`, `getPublishedInYear()==1758`.
- `splitsPipeJoinedAuthorsAndExAuthors`: combinationAuthorship `"Mill.|L."`, combinationExAuthorship `"Sm."` → `getCombinationAuthorship().getAuthors()==["Mill.","L."]`, `getExAuthors()==["Sm."]`.
- `stripsBracketsOfABracketedYear`: basionymAuthorshipYear `"[1789]"` → `getBasionymAuthorship().getYear()=="1789"`.
- `unparsedUsageMapsWithoutError`: only scientificName `"Foo bar baz"` and authorship `"Someone"` set, rank null → no exception, `getRank()==UNRANKED`, combination authorship empty, `getAuthorship()` still the raw string.

`RelTypesTest`:
- `parsesRawColdpSpellings`: `"BASIONYM"`, `"basionym"`, `"spelling-correction"`, `"Spelling_Correction"`, `" spelling correction "` → BASIONYM / SPELLING_CORRECTION.
- `unknownOrBlankIsNull`: `"foo"`, `""`, `null` → null.
- `homotypicLabelsMatchTheEnum`: `HOMOTYPIC_LABELS` contains exactly `basionym, homotypic, spelling correction, based on, replacement name, superfluous`.

- [ ] **Step 2: Run** `mvn -o test -Dtest=org.catalogueoflife.editor.nomenclature.ClbNamesTest+org.catalogueoflife.editor.nomenclature.RelTypesTest` — Expected: FAIL (classes missing).
- [ ] **Step 3: Implement.** Add the dependency (version `1.7.0-SNAPSHOT`, both exclusions). `ClbNames` uses `NameParserService.parseRank`, sets scientificName, authorship, uninomial, genus, infragenericEpithet, specificEpithet, infraspecificEpithet, cultivarEpithet, notho, type (nameType), nomStatus, publishedInYear, code, both authorships. `RelTypes.parse`: normalize, then `NomRelType.valueOf(upper with "_")`, catching `IllegalArgumentException` → null.
- [ ] **Step 4: Run** the same command and `mvn -o clean test` — Expected: PASS, all unit tests green.
- [ ] **Step 5: Commit** — `feat(nomenclature): depend on the CLB nomenclature module, map usages to its Name`.

### Task 3: `HomotypicRelations` → `NomRelType` (Part 1a)

**Files:**
- Delete: `backend/src/main/java/org/catalogueoflife/editor/name/homotypy/HomotypicRelations.java`
- Modify: `name/homotypy/SynonymyMapper.java` (type list as parameter), `name/homotypy/HomotypyService.java` (`existingRelationKeys`), `name/homotypy/HomotypyDetector.java:94`
- Test: `backend/src/test/java/org/catalogueoflife/editor/name/homotypy/HomotypyApiIT.java` (existing, must stay green)

**Interfaces:**
- Consumes: `RelTypes.parse`, `RelTypes.label`, `RelTypes.HOMOTYPIC_LABELS` (Task 2).
- Produces: `SynonymyMapper.homotypicClosure(int projectId, int usageId, List<String> types) -> List<Integer>`; callers pass `RelTypes.HOMOTYPIC_LABELS`.

- [ ] **Step 1:** Add to `HomotypyApiIT` a test `synonymyGroupsAnUppercaseImportedBasionym`: insert a relation with stored type `"BASIONYM"` between a synonym and the accepted name, `GET .../synonymy` lists the synonym under `homotypic`. Run the IT; expected PASS already (guards the refactor).
- [ ] **Step 2:** Replace the SQL `IN (...)` literal with a MyBatis `<foreach>` over `types` (script select); replace `HomotypicRelations.normalize(type)` keys with `RelTypes.label(RelTypes.parse(type))` (skip null types); delete the class.
- [ ] **Step 3: Run** `mvn -o clean test-compile` and the ITs `HomotypyApiIT`, `ConsolidationApiIT` — Expected: PASS.
- [ ] **Step 4: Commit** — `refactor(homotypy): take the homotypic relation types from NomRelType`.

### Task 4: `NameMatcher` authors via `AuthorComparator` (Part 1b)

**Files:**
- Modify: `backend/src/main/java/org/catalogueoflife/editor/merge/NameMatcher.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/merge/NameMatcherTest.java`; check `MergeApplyModesIT`, `MergeApplyNamesIT`, `NameMatcherIT`

**Interfaces:**
- Consumes: `ClbNames.toName`, `ClbNames.authorship` (Task 2).
- Produces: `static boolean authorCompatible(NameUsage a, NameUsage b)` (replaces the String overload), a `private static final AuthorComparator AUTHORS = new AuthorComparator(AuthorshipNormalizer.INSTANCE)`.

- [ ] **Step 1: Write the failing tests** in `NameMatcherTest` (build usages with atomized authorship fields):
  - `abbreviatedAndFullAuthorAreCompatible`: combination `"L."` vs `"Linnaeus"` → true.
  - `differentAuthorsAreIncompatible`: `"Mill."` vs `"L."` → false.
  - `blankRawAuthorshipIsCompatible`: one side with no authorship at all → true.
  - `unparsedNamesFallBackToRawStrings`: both without atomized authorship, raw `authorship` `"Someone"` vs `"Other"` → false; `"Someone"` vs `"someone"` → true.
  - Rewrite the existing string-based cases onto usages, keeping their expectations except where the comparator legitimately changes them (record which in the test name/comment).
- [ ] **Step 2: Run** `mvn -o test -Dtest=org.catalogueoflife.editor.merge.NameMatcherTest` — Expected: FAIL (signature).
- [ ] **Step 3: Implement.** Raw `authorship` blank on either side → compatible. Either side with raw authorship but no atomized combination and basionym authorship → old normalized-string rule. Otherwise compatible iff `AUTHORS.compare(toName(a), toName(b)) != Equality.DIFFERENT` (the `ScientificName` overload, as CLB's matching uses it). `matchOne` passes the usages.
- [ ] **Step 4: Run** the unit test, then ITs `NameMatcherIT`, `MergeApplyModesIT`, `MergeApplyNamesIT`, `MergePlanIT`. Update fixtures or comments only where a category changes because the authors are the same people written differently. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(merge): compare authors with CLB's AuthorComparator`.

### Task 5: `HomotypyDetector` on `BasionymSorter` (Part 1c)

**Files:**
- Create: `backend/src/main/java/org/catalogueoflife/editor/name/homotypy/ExistingRelations.java`
- Modify: `name/homotypy/HomotypyDetector.java` (rewrite internals), `name/homotypy/HomotypyService.java`, `name/homotypy/ConsolidationService.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/name/homotypy/HomotypyDetectorTest.java` (rewrite), `ExistingRelationsTest.java`; `HomotypyApiIT`, `ConsolidationApiIT`

**Interfaces:**
- Consumes: `ClbNames.toName`, `RelTypes` (Task 2); module `BasionymSorter`, `HomotypicGroup`, `HomotypicGroup.Evidence`, `HomotypicRules`, `AuthorComparator`.
- Produces:
  - `ExistingRelations.of(Collection<NameRelationResponse> relations) -> ExistingRelations` (skips null related ids and unknown types)
  - `boolean exists(int from, int to, NomRelType type)`
  - `Set<Integer> homotypic(int usageId)` (either direction)
  - `boolean linkedByData(int a, int b)` (directly or through one other usage)
  - `boolean laterHomonyms(int a, int b)` (either direction)
  - `boolean misspelling(int usageId)` (is the target of a spelling correction relation)
  - `ExistingRelations.EMPTY`
  - `HomotypyDetector.detect(NameUsage accepted, List<NameUsage> synonyms, ExistingRelations existing, @Nullable NomCode code) -> HomotypyProposal`
  - `HomotypyDetector.group(List<NameUsage> candidates, Map<Integer, List<Integer>> acceptedBySynonym, ExistingRelations existing, @Nullable NomCode code) -> HomotypyProposal`
  - `ProposedRelation`/`ProposedGroup`/`HomotypyProposal` unchanged.

- [ ] **Step 1: Write the failing tests.**

`ExistingRelationsTest`: `homotypicIsSymmetric`, `linkedThroughOneOtherUsage` (1↔2 basionym, 3↔2 spelling correction → `linkedByData(1,3)`), `laterHomonymEitherDirection`, `misspellingIsTargetOfSpellingCorrection`, `skipsDanglingAndUnknownTypes` (relatedUsageId null, type `"foo"`).

`HomotypyDetectorTest` (rewrite; zoological code; usages built with atomized fields):
- `abbreviatedAuthorGroups`: `Aus bus L.` + `Cus bus (Linnaeus)` → one group, basionym = Aus bus, relation `basionym` from Cus bus to Aus bus.
- `yearWithinToleranceGroups`: `Aus bus Smith, 1850` + `Cus bus (Smith, 1851)` → one group.
- `differentAuthorsDoNotGroup`: `Aus bus L.` + `Cus bus (Mill.)` → no relation proposed.
- `originalsInTwoGeneraGroupWhenSynonymyLinksThem`: `Aus bus L.` accepted, `Cus bus L.` its synonym → one group.
- `originalsInTwoGeneraStayApartWhenNothingLinksThem`: both accepted, no relation → two groups.
- `knownMisspellingNeverBasionym`: `Aus bus L.` and `Aus buss L.` with an existing spelling correction from `Aus bus` to `Aus buss`, plus `Cus bus (L.)` → basionym = Aus bus; recombination's `basionym` points at Aus bus.
- `noRelationBetweenLinkedOrHomonymNames`: the pair already linked through one other name, and a pair related as later homonyms → no proposed relation for those pairs.
- `misappliedExcluded` (keep), `existingRelationMarkedExists` (keep, via `ExistingRelations`).

- [ ] **Step 2: Run** `mvn -o test -Dtest=org.catalogueoflife.editor.name.homotypy.HomotypyDetectorTest+org.catalogueoflife.editor.name.homotypy.ExistingRelationsTest` — Expected: FAIL.
- [ ] **Step 3: Implement `ExistingRelations`** (maps keyed by usage id).
- [ ] **Step 4: Implement the detector.**
  - Bucket the candidates by `SciNameNormalizer.normalizeEpithet(terminal epithet)`, skipping supraspecific ranks, autonyms, and names without an epithet.
  - Per bucket, call `new BasionymSorter<Name>(new AuthorComparator(AuthorshipNormalizer.INSTANCE), n -> 0).groupBasionyms(code, epithet, names, n -> n, linked, evidence)`.
  - `linked`: one is a synonym of the other via `acceptedBySynonym`, or both are synonyms of the same accepted name, or `existing.homotypic(a)` contains b. `detect` derives `acceptedBySynonym` from its arguments.
  - `evidence.isVariant(n)` = `existing.misspelling(id)`. `evidence.penalty(n)` = 0 for an accepted available name, 1 for a synonym, +1 if `!n.isAvailable()`.
  - Map the groups to `ProposedGroup`s with the types and directions in the spec (Part 1c).
  - Drop a proposed relation when `existing.linkedByData` or `existing.laterHomonyms` holds for its pair. Set `exists` via `existing.exists`.
- [ ] **Step 5: Wire the callers.**
  - `HomotypyService.detect` builds `ExistingRelations.of(...)` from `findByUsage` of the accepted name and its synonyms, and passes `project.getNomCode()`.
  - `ConsolidationService.scan` builds it from the relations among the candidates (one `findByProject`, filtered to candidate ids) and passes `acceptedBySynonym`.
- [ ] **Step 6: Run** the unit tests, then ITs `HomotypyApiIT`, `ConsolidationApiIT`. Adjust an IT expectation only where the better grouping legitimately changes it, saying why in a comment. Expected: PASS.
- [ ] **Step 7: Run** `mvn -o verify` — Expected: BUILD SUCCESS.
- [ ] **Step 8: Commit** — `feat(homotypy): group homotypic names with CLB's BasionymSorter`.

### Task 6: Relation cleanup scan endpoint

**Files:**
- Create: `backend/src/main/java/org/catalogueoflife/editor/name/homotypy/NameRelationCleanupService.java`, `NameRelationCleanupController.java`
- Create: `name/homotypy/dto/RelationConflictPair.java`, `dto/JudgedRelation.java`
- Test: `backend/src/test/java/org/catalogueoflife/editor/name/homotypy/NameRelationCleanupApiIT.java`

**Interfaces:**
- Consumes: `ClbNames.toName`, `RelTypes.parse` (Task 2); module `NameRelationJudge`, `NameRelationRules.Rel`; `NameUsageMapper.findAllByProject`, `findSubtreeIds`, `SynonymAcceptedMapper.findAllLinks`, `NameRelationMapper.findByProject`.
- Produces:
  - `GET /api/projects/{pid}/name-relations/conflicts?rootId={optional}` → `List<RelationConflictPair>`, any project role (`projects.requireVisible`), 404 for an unknown rootId.
  - `record RelationConflictPair(PairName n1, PairName n2, List<JudgedRelation> relations)`; `record PairName(int id, String label, String status)`
  - `record JudgedRelation(int id, int version, String type, int usageId, int relatedUsageId, Integer referenceId, String page, String remarks, List<String> issues, boolean remove, boolean duplicate, boolean preselected)`. `issues` holds CLB `Issue` names, plus `"DUPLICATE"` for an exact duplicate. A relation without a verdict has empty issues and `preselected=false`.

- [ ] **Step 1: Write the failing IT** `NameRelationCleanupApiIT` (helpers as in `ConsolidationApiIT`, zoological project), seeding a family with species:
  - `basionymAndSpellingCorrectionPair`: `Aus bus` and `Aus buss` related as both basionym and spelling correction → one pair; the basionym has `remove=true, preselected=true`, the spelling correction is kept.
  - `laterHomonymWithHomotypicRelation`: the homotypic relation is `remove=true`.
  - `relationGivenBothWays`: the direction that does not fit the names is `remove=true`.
  - `exactDuplicateWithoutExtraContentIsPreselected`: two identical basionym rows, neither has a reference → one `duplicate=true, preselected=true`, issues `["DUPLICATE"]`.
  - `duplicateCarryingAReferenceIsKeptOrUnticked`: the second row has a referenceId, the first none → the referenced row is the one kept; the other row is `duplicate=true, preselected=true`. Swap so that both rows carry different remarks → the duplicate is `preselected=false`.
  - `cleanPairNotListed`: a single basionym relation → no pair.
  - `subtreeScope`: pair with one usage in the subtree is listed with `rootId`; pair with both outside is not; without `rootId` both are listed.
  - `danglingAndSelfRelationsSkipped`: relatedUsageId null, a relation to itself, and type `"foo"` → no error, not listed.
  - `rawTypeSpellingsJudged`: the basionym + spelling correction pair stored as `"BASIONYM"` and `"spelling-correction"` → listed as in the first case.
  - `unparsedNamesJudgedWithoutError`: a pair of usages with no rank and no atomized parts, related twice as `homotypic` and `basionym` → 200, no exception.
  - `viewerCanScan`: viewer gets 200.
  - `wholeProjectScanBulkLoads`: with 50 pairs, the scan completes and lists all; implementation loads usages with one `findAllByProject` (assert with a `@MockitoSpyBean NameUsageMapper` that `findByIdInProject` is never called during the scan).
- [ ] **Step 2: Run** `mvn -o test-compile failsafe:integration-test failsafe:verify -Dit.test=org.catalogueoflife.editor.name.homotypy.NameRelationCleanupApiIT` — Expected: FAIL (404 / missing classes).
- [ ] **Step 3: Implement the scan.**
  - Bulk-load the usages, links and relations. With `rootId`, the scope is `findSubtreeIds(rootId)` plus the synonyms of those ids, and a relation is a candidate if either usage is in scope.
  - Map the usages with `ClbNames.toName` and the project code.
  - `new NameRelationJudge<NameRelationResponse>(r -> new Rel(RelTypes.parse(r.type()), String.valueOf(r.usageId()), String.valueOf(r.relatedUsageId()))).judge(candidates, names::get)`, after filtering out relations with a null related id or an unknown type.
  - Per pair, merge the verdicts by relation. `remove` = any verdict removes; `preselected = remove || duplicate`, except that a duplicate is only preselected when it carries no reference, page or remarks the kept row lacks.
  - The kept row of a duplicate set is chosen by the service: the most of reference, page and remarks filled, then the lowest id. Swap the judge's choice when it differs.
  - Add the pair's verdict-free relations for context. Labels come from `NameParserService.formatName(u, code, false)`.
- [ ] **Step 4: Run** the IT — Expected: PASS.
- [ ] **Step 5: Commit** — `feat(relations): scan a subtree or project for conflicting name relations`.

### Task 7: Relation cleanup apply endpoint

**Files:**
- Modify: `backend/src/main/java/org/catalogueoflife/editor/child/NameRelationService.java` (extract `deleteWithAudit`)
- Modify: `name/homotypy/NameRelationCleanupService.java`, `NameRelationCleanupController.java`
- Modify: `child/NameRelationMapper.java` (versioned delete)
- Create: `name/homotypy/dto/CleanupRequest.java`
- Test: `NameRelationCleanupApiIT.java` (extend)

**Interfaces:**
- Consumes: Task 6 service.
- Produces:
  - `POST /api/projects/{pid}/name-relations/cleanup` body `CleanupRequest(List<RelationRef> relations)`, `record RelationRef(int id, int version)` → `{"deleted": n}`; owner/editor only (403 otherwise); `@Transactional`; 409 if any relation is missing or its version differs, nothing deleted.
  - `NameRelationMapper.deleteVersioned(int projectId, int id, int version) -> int`
  - `NameRelationService.deleteWithAudit(int userId, int projectId, NameRelationResponse before)` used by both `delete` and the cleanup (audit DELETE + `ValidationEvent.forUsage(projectId, before.usageId())`).

- [ ] **Step 1: Write the failing tests** (extend the IT):
  - `applyDeletesExactlyTheSentRelations`: scan, send two of three listed relations (one flag-only), → `deleted=2`; the third still exists; two audit rows with operation DELETE and the before image.
  - `staleVersionFailsWholeRequest`: update one relation after the scan, send both → 409, both still exist, no audit rows.
  - `missingRelationFailsWholeRequest`: one id deleted meanwhile → 409.
  - `viewerCannotApply`: 403.
- [ ] **Step 2: Run** the IT — Expected: FAIL.
- [ ] **Step 3: Implement** with the versioned delete (`DELETE ... AND version = #{version}`, 0 rows → throw 409 so the transaction rolls back) and the extracted audit path. `NameRelationService.delete` keeps its current behaviour.
- [ ] **Step 4: Run** the IT and `NameRelationIT` — Expected: PASS.
- [ ] **Step 5: Commit** — `feat(relations): delete reviewed name relations with version check and audit`.

### Task 8: Review page and entry points

**Files:**
- Create: `frontend/src/api/relationCleanup.ts`, `frontend/src/homotypy/RelationConflictsPage.tsx`, `frontend/src/homotypy/RelationConflictsPage.test.tsx`, `frontend/src/help/pages/relation-cleanup.md`
- Modify: `frontend/src/App.tsx` (routes), `frontend/src/names/NameActionMenu.tsx` (menu item after "Find homotypic conflicts"), `frontend/src/projects/ProjectMetadataPage.tsx` (project-wide block next to "Link genera"), `frontend/src/help/toc.ts`, `frontend/src/test/server.ts` (default handlers)

**Interfaces:**
- Consumes: Task 6/7 endpoints and DTO shapes.
- Produces:
  - `getRelationConflicts(pid: number, rootId?: number): Promise<RelationConflictPair[]>`
  - `cleanupRelations(pid: number, relations: { id: number; version: number }[]): Promise<{ deleted: number }>`
  - Routes `relation-conflicts` and `relation-conflicts/:rootId` under `projects/:projectId`.
  - Issue copy map: `NAME_RELATION_CONFLICT` → "Contradicts another relation of these names", `NAME_RELATION_REDUNDANT` → "Repeats what another relation says", `SPELLING_CORRECTION_IMPLAUSIBLE` → "The names differ in more than their spelling", `DUPLICATE` → "Exact duplicate". Unknown issue names are shown as-is.

- [ ] **Step 1: Write the failing tests** (`RelationConflictsPage.test.tsx`, MSW):
  - `preselectsRemovalsAndPlainDuplicates`: checkboxes for `preselected` relations are checked; a flag-only relation and a content-carrying duplicate are unchecked; context relations have no checkbox.
  - `deleteSendsTheTickedRelationsWithVersions`: tick a flag-only one, untick a removal, confirm → POST body equals the checked `{id, version}` list exactly; then the list refetches.
  - `conflictShowsMessageAndRefetches`: POST returns 409 → error notification, GET called again.
  - `emptyState`: no pairs → "No conflicting name relations".
  - `viewerSeesNoCheckboxes`: viewer role → no checkboxes, no delete button.
- [ ] **Step 2: Run** `npx vitest run src/homotypy/RelationConflictsPage.test.tsx` — Expected: FAIL.
- [ ] **Step 3: Implement** the page modelled on `HomotypicConflictsPage`. Each pair is a card showing both names and its relations; each relation shows its type, from → to, reference/page/remarks and its issue labels. A header `HelpLink` to the new help page, and one "Delete selected (n)" button with a confirm modal stating the count. Add the menu item "Clean name relations", the project-wide block "Clean name relations" for editors on `ProjectMetadataPage`, the routes, and the help page explaining the four issues and that nothing is deleted unless ticked. Link the help page from the glossary/validation pages where relations are explained.
- [ ] **Step 4: Run** `npm test` and `npm run build` — Expected: all green, no type errors.
- [ ] **Step 5: Commit** — `feat(relations): review page to clean up conflicting name relations`.

### Task 9: Wrap-up

**Files:**
- Modify: `backlog.md` (entry under the homotypy items, shipped), `docs/superpowers/specs/2026-10-29-nomenclature-module-design.md` (Status: Implemented 2026-10-30, plan link)

- [ ] **Step 1: Run** `cd backend && mvn -o clean verify` and `cd frontend && npm run build && npm test` — Expected: BUILD SUCCESS, all tests green.
- [ ] **Step 2: Commit** — `docs: mark the nomenclature module work as shipped`.
- [ ] **Step 3:** `git push origin main` (triggers the Jenkins dev deploy).
