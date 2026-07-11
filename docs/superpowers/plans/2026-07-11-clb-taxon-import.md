# Direct CLB Taxon Import — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A taxon context-menu action that pulls a chosen ChecklistBank taxon (its subtree / only its children / just its info) straight into the focal project taxon via CLB's JSON API — inserted directly, no matching/merge/staging/transaction.

**Architecture:** A CLB read client (`ClbImportClient`, mirrors `ClbMatchClient`) fetches `UsageInfo` bundles + children; a mapper turns the CLB `api` model (on the classpath via `reader`→`api`, Jackson 2) into our create records; `ClbImportService` inserts them under the focal taxon using the existing create/mapper path in three modes. A modal (URL-paste or dataset+taxon suggest) drives it.

**Tech Stack:** Spring Boot 4.1 / Java 25 / Postgres 17 / MyBatis; `life.catalogue.api.model.*` (Jackson 2); React 18 / Mantine 7 / Vitest + MSW.

## Global Constraints

- Build/test with **JDK 25 via `current`**: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw ...`.
- **Run `clean verify` (not incremental) when a record's arity/component types change.**
- Commit directly to `main`; one commit per task after tests pass.
- Spec: `docs/superpowers/specs/2026-07-11-clb-taxon-import-design.md`. **No new migration** (reuses existing tables).
- **Reuse (committed):** `name/ClbMatchClient.java` (the CLB client mirror: `RestClient`, `coldp.clb.base-url`, `verbose`, error mapping); the ColDP import's child-entity insert logic in `coldp/imprt/ImportRunService.java` (the 7 child mappers, `taxonID`/`nameID` remap, the accepted-only guard, `refIdMap`/`usageIdMap`); `NameUsageService`/`NameUsageMapper.insert`/`updateHierarchy`, `SynonymAcceptedMapper.link`, `TaxonInfoMapper.upsert`, `IdSeqMapper.allocate`, `ReferenceMapper.insert`, the 7 child mappers + Request DTOs, `NameUsageService.mergeScopedId` (provenance CURIE), `NameParserService.parseInto`.
- **Package:** `org.catalogueoflife.editor.clb`.
- **CLB `api` model is Jackson 2** (`com.fasterxml.jackson`), distinct from our Jackson 3 (`tools.jackson`). The client fetches the JSON body as text and deserializes it with a **Jackson 2 `com.fasterxml.jackson.databind.ObjectMapper`** into `UsageInfo`/`Dataset`/`NameUsageBase`. Both Jacksons coexist (already true via the reader dep).

## `UsageInfo` bundle (what `/taxon/{id}/info` returns — the mapping source)
`life.catalogue.api.model.UsageInfo`: `usage` (NameUsageBase → Taxon/Synonym with its `Name`), `synonyms` (`Synonymy`: homotypic/heterotypic/misapplied), `distributions`, `vernacularNames`, `media`, `properties` (TaxonProperty), `estimates` (SpeciesEstimate), `typeMaterial` (Map<nameId,List<TypeMaterial>>), `nameRelations` (List<NameUsageRelation>), `publishedIn` (Reference), `references` (Map<id,Reference> — resolve every referenceID), `names`/`taxa` (Maps).

---

### Task 1: `ClbImportClient` + api-model → our-model mapping

**Files:** Create `clb/ClbImportClient.java`, `clb/ClbUsageMapper.java`, `clb/ClbTaxonUrl.java` (URL parse). Test: `clb/ClbUsageMapperTest.java`, `clb/ClbTaxonUrlTest.java`, `clb/ClbImportClientIT.java` (mocked CLB).

**Interfaces:**
- Produces: `ClbImportClient.searchDatasets(String q) : List<ClbDatasetHit>`, `searchUsages(String datasetKey, String q, String rank) : List<ClbUsageHit>`, `usageInfo(String datasetKey, String id) : UsageInfo`, `childrenIds(String datasetKey, String id) : List<String>`. `ClbUsageMapper.toCreateRequest(UsageInfo, ...)` producing our name-usage + children. `ClbTaxonUrl.parse(String url) : Optional<ClbRef(datasetKey, taxonId)>`.

- [ ] **Step 1: `ClbTaxonUrl.parse`** (unit-first) — lenient regex over a pasted URL:
  - `…checklistbank.org/dataset/{key}/taxon/{id}` and `…/dataset/{key}/nameusage/{id}` → `(key, id)`.
  - `…catalogueoflife.org/data/taxon/{id}` → `("3LXR", id)` (the COL release alias).
  - else `Optional.empty()`. `record ClbRef(String datasetKey, String taxonId)`. Test each pattern + a junk string.
- [ ] **Step 2: `ClbImportClient`** — mirror `ClbMatchClient` (inject `RestClient`/builder + `@Value("${coldp.clb.base-url}")`; a private `com.fasterxml.jackson.databind.ObjectMapper` for the api model). Methods GET:
  - `searchDatasets(q)` → `/dataset?q={q}&limit=20` → parse `.result[]` into `record ClbDatasetHit(String key, String title, String alias)`.
  - `searchUsages(datasetKey, q, rank)` → `/dataset/{key}/nameusage?q={q}&rank={rank}&limit=20` → `record ClbUsageHit(String id, String scientificName, String rank, String status)`.
  - `usageInfo(datasetKey, id)` → `/dataset/{key}/taxon/{id}/info` → deserialize the body into `life.catalogue.api.model.UsageInfo` (Jackson 2). On 404 → a clear `ResponseStatusException(NOT_FOUND, "CLB taxon not found")`; on 5xx → 502 (mirror `ClbMatchClient`).
  - `childrenIds(datasetKey, id)` → the CLB tree-children endpoint (**resolve the exact path**: `GET /dataset/{key}/tree/{id}/children` returns `ResultPage<TreeNode>`; else `GET /dataset/{key}/nameusage?parentID={id}` — grep the CLB `TreeResource`/`NameUsageResource` in `~/code/col/backend` to pick the one that lists direct accepted children) → their ids. Handle paging (loop until all children fetched).
- [ ] **Step 3: `ClbUsageMapper`** (unit-first, pure mapping — no DB) — `UsageInfo` → our records:
  - **Name/usage:** map `usage.getName()` atomized fields (`getUninomial/getGenus/getInfragenericEpithet/getSpecificEpithet/getInfraspecificEpithet/getCultivarEpithet/getNotho`), `getScientificName`, `getAuthorship`, `getRank().name().toLowerCase()`, status (`TaxonomicStatus` → our `Status`: ACCEPTED / SYNONYM / MISAPPLIED / PROVISIONALLY_ACCEPTED→UNASSESSED), `getPublishedInYear`/page, `getNomStatus`, remarks. Produce a `CreateNameUsageRequest`-shaped record (or set fields on a `NameUsage`). Re-`parseInto` on insert (Task 2) is the safety net.
  - **Synonyms:** flatten `synonyms` (`Synonymy` homotypic + heterotypic + misapplied) into a list of synonym `Name`s + their status (misapplied → MISAPPLIED else SYNONYM).
  - **Child entities:** map each `Distribution`/`VernacularName`/`Media`/`TaxonProperty`/`SpeciesEstimate`/`TypeMaterial`/`NameUsageRelation` in `UsageInfo` → the matching `<X>Request` DTO (the inverse of the ColDP export/import child field mapping — mirror `ImportRunService`'s child load field-for-field; enums via lowercase name; referenceID via the caller's ref-id remap).
  - **References:** expose `UsageInfo.getReferences()` (Map<clbId,Reference>) + `publishedIn` so Task 2 can insert + remap. Map a CLB `Reference` → our `Reference` (citation/type/author/title/…/doi/… — the same fields the ColDP `ReferenceColdpWriter`/import handle).
  - Unit tests: a `UsageInfo` fixture (built in-test from the api model) → assert the mapped name fields, status inverse, synonyms, one of each child type, references.

- [ ] **Step 4: Clean verify + commit.** `ClbImportClientIT` mocks CLB (WireMock or a `@MockitoBean RestClient`-style stub — mirror `ClbMatchIT`'s mocking): assert `usageInfo` deserializes a canned CLB `/info` JSON into `UsageInfo`, `searchDatasets`/`searchUsages` parse hits, `childrenIds` pages. `clean verify`. Commit `feat(clb): CLB import client + UsageInfo->model mapping + URL parse`.

---

### Task 2: `ClbImportService` + endpoint (the three modes)

**Files:** Create `clb/ClbImportService.java`, `clb/ClbImportController.java`, `clb/dto/{ClbImportRequest,ClbImportSummary}.java`, `clb/ImportMode.java` (`TAXON_SUBTREE, CHILDREN_ONLY, UPDATE_FOCAL`). Test: `clb/ClbImportServiceIT.java`.

**Interfaces:**
- Consumes: `ClbImportClient`, `ClbUsageMapper`, the insert primitives (see Global Constraints).
- Produces: `ClbImportService.importFromClb(int userId, int projectId, int focalUsageId, String datasetKey, String sourceTaxonId, ImportMode mode, Set<String> entityTypes) : ClbImportSummary`. Endpoint `POST /api/projects/{pid}/usages/{focalId}/clb-import`.

- [ ] **Step 1: gather + guard.** Owner/editor on the project; the **focal usage must exist and be ACCEPTED** (400 else). Determine the source usages to insert per `mode`:
  - `TAXON_SUBTREE`: the source usage + recurse `childrenIds` depth-first.
  - `CHILDREN_ONLY`: the source's `childrenIds` + recurse (source itself skipped).
  - `UPDATE_FOCAL`: none (attach to focal — Step 3).
  Enforce a size cap (`@Value("${coldp.clb-import.max-usages:500}")`): if the gathered set exceeds it → 400 "subtree too large (N); pick a smaller root or use ColDP import". Fetch each usage's `UsageInfo` via the client.
- [ ] **Step 2: insert accepted subtree (modes A/B).** Insert top-down so parents exist first. For each gathered usage: allocate `id = idSeq.allocate(pid,"name_usage")`, build the target `NameUsage` (via `ClbUsageMapper` + `parseInto`), set `parent_id` = the focal id for a root of the imported set, else the already-inserted parent's new id (keep a `Map<clbId,newId>`); `insert`; `taxonInfo.upsert` if it carries extinct/env/temporal. Then its **synonyms** → for each, allocate an id, insert a synonym usage, `synonymAccepted.link(pid, synNewId, acceptedNewId, ordinal)`. Then its **child entities** (per the chosen `entityTypes`, or all for A/B) → resolve `referenceID` via the ref map (Step 4), allocate + insert via the raw child mappers (accepted-only guard for the 5 taxon-scoped, like import). Provenance CURIE `<scope>:<clbId>` on `alternative_id` (`col:` when `datasetKey`=="3LXR" or a COL release, else `datasetKey:`).
- [ ] **Step 3: update-focal (mode C).** Fetch the source `UsageInfo`; attach to the **focal** usage id (no new accepted names): its **synonyms** → `synonymAccepted.link(pid, <new synonym usage>, focalId, ordinal)` (insert the synonym usages, link to focal); the chosen **child entities** → insert against the focal id (accepted-only ok — focal is accepted). Respect `entityTypes`.
- [ ] **Step 4: references.** Before inserting name/child references, insert the pull's references (from each `UsageInfo.getReferences()`), deduped **within this import** by CLB ref id → `Map<clbRefId,newRefId>`; a `publishedIn`/`referenceID`/child `referenceID` remaps through it. (Duplicates against existing target refs are allowed — no matching.) Insert via `ReferenceMapper.insert` + `idSeq.allocate(pid,"reference")` + provenance CURIE.
- [ ] **Step 5: no transaction + summary.** Do NOT wrap in one transaction (per spec — small, additive; reuse the per-usage service calls or raw mapper inserts committing as they go). Return `ClbImportSummary` (counts: nameUsages, synonyms, references, per child type; a list of any per-record issues). Controller: `POST /api/projects/{pid}/usages/{focalId}/clb-import` (body `record ClbImportRequest(String datasetKey, String sourceTaxonId, ImportMode mode, Set<String> entityTypes)`) → 200 summary; owner/editor.
- [ ] **Step 6: IT + clean verify + commit.** `ClbImportServiceIT` (mock `ClbImportClient` to return canned `UsageInfo`s + children): **mode A** (a genus source with 2 species + a synonym + a distribution + a ref → all inserted under the focal, provenance CURIEs, synonym linked, child + ref created); **mode B** (children only — the genus itself NOT inserted); **mode C** (focal gains the source's synonyms + only the checked entity types, no new accepted children); the **size cap** (mock > cap children → 400); the accepted-only child guard. `clean verify`. Commit `feat(clb): CLB import service + endpoint (subtree / children-only / update-focal)`.

---

### Task 3: Frontend — context-menu entry + `ClbImportModal` + suggest proxy

**Files:** Create `frontend/src/api/clb.ts`, `frontend/src/clb/ClbImportModal.tsx`; modify the tree action menu (grep the ⋮/right-click menu component, e.g. `frontend/src/tree/`); backend `clb/ClbImportController.java` (add the suggest proxy endpoints). Test: `frontend/src/clb/ClbImportModal.test.tsx`.

- [ ] **Step 1: suggest proxy (backend).** Add to `ClbImportController`: `GET /api/clb/datasets?q=` → `ClbImportClient.searchDatasets`; `GET /api/clb/{datasetKey}/usages?q=&rank=` → `searchUsages`; `GET /api/clb/{datasetKey}/resolve/{taxonId}` → a light `{datasetKey, taxonId, scientificName, rank}` (resolve a pasted-URL taxon to show its name). Authenticated. (Proxying avoids browser CORS + keeps the CLB base-url server-side.)
- [ ] **Step 2: `api/clb.ts`** — `parseClbUrl(url)` (client-side mirror of `ClbTaxonUrl` for instant feedback, backend `resolve` confirms the name); `searchClbDatasets(q)`, `searchClbUsages(datasetKey, q, rank?)`, `resolveClbTaxon(datasetKey, taxonId)`, `clbImport(projectId, focalId, {datasetKey, sourceTaxonId, mode, entityTypes})`.
- [ ] **Step 3: `ClbImportModal`** (props: projectId, focalUsage) —
  - A **source** section with two tabs/toggle: **Paste URL** (a TextInput; on a valid parse show the resolved taxon name + dataset) OR **Search** (a dataset `Autocomplete` → a taxon `Autocomplete` with an optional rank `Select`).
  - A **mode** radio: Taxon + subtree · Children only · Update this taxon.
  - When mode = Update-focal (and optionally for A/B), **entity-type checkboxes** (Synonyms, Vernacular, Distributions, Type material, Media, Estimates, Properties, Name relations) — default all on.
  - **Import** → `clbImport(...)` → a summary (counts) + on success invalidate the focal's children + detail queries (so the tree/detail refresh) and close.
- [ ] **Step 4: context-menu entry.** Add **"Import from ChecklistBank"** to the tree ⋮/right-click menu on **accepted** taxa (mirror the existing add-child/add-synonym actions' gating) → opens `ClbImportModal` with that taxon as focal.
- [ ] **Step 5: test + gates + commit.** `ClbImportModal.test.tsx` (MSW): URL paste parses + resolves + fills the source; the search path (dataset→taxon autocompletes); mode C reveals the entity checkboxes; Import posts the right body + shows the summary. `vitest` + `tsc --noEmit` + build. Commit `feat(clb): Import from ChecklistBank context action + modal + suggest proxy`.

---

## Self-Review notes
- **Spec coverage:** client + mapping + URL parse = T1; the three modes + endpoint + size cap + provenance + no-transaction = T2; context menu + modal (URL paste + suggest + modes + entity picker) + suggest proxy = T3.
- **Reuse:** `ClbImportClient` mirrors `ClbMatchClient`; the insert path + child mapping mirror `ImportRunService` (the accepted-only guard, `refIdMap`-style remap, two-phase not needed since we insert top-down under an existing focal parent — parents precede children); provenance via `mergeScopedId`.
- **Type consistency:** `UsageInfo`→our records in `ClbUsageMapper`; `Map<clbId,newId>` for usages + `Map<clbRefId,newRefId>` for refs thread T2; `ImportMode`/`entityTypes` flow request→service→modes; CURIE scope `col:`/`<datasetKey>:`.
- **No matching/merge/transaction** (per spec) — duplicates on re-pull are intended; the reconciling path is ColDP import + supervised merge; a future ref-dedup tool cleans refs.
- **Jackson 2 for the api model** (client only); the rest of the app stays Jackson 3.
