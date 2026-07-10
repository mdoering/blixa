# Taxon form + References tab + project identifiers — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add published-in reference + remarks + project-configured identifier fields to the taxon Details form, drop the unused general `link`, and add a References tab (taxonomic references + web-URL references).

**Architecture:** Reuses existing patterns — the `coldp_id`-drop refactor, the `PUT …/identifiers` optimistic-locked setter, the `gbifOccurrenceLayer` project-setting wiring, `CrossrefClient` (for the SSRF-guarded `WebPageClient`), `EntitySelect`, and the Mantine Tabs `TaxonDetail`.

**Tech Stack:** Spring Boot 4.1 / Java 25 / Postgres 17 / MyBatis / Flyway; React 18 / Mantine 7 / @tanstack/react-query / Vitest + MSW.

## Global Constraints

- Build/test with JDK 25: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/25.0.1-librca ./mvnw ...`.
- Commit directly to `main`; one commit per task after tests pass.
- Spec: `docs/superpowers/specs/2026-07-10-taxon-form-references-identifiers-design.md`.
- Next migration after V15 is **V16**.
- Request records use boxed `Integer version`; wire status UPPERCASE / rank lowercase; `RestClient.builder()` static.

---

### Task 1: V16 — drop `name_usage.link`; add `project.identifier_scopes` + `reference.accessed`

**Files:** Create `backend/.../db/migration/V16__taxon_form_fields.sql`; modify `name/NameUsage.java`, `NameUsageMapper.java`, `dto/{NameUsageResponse,UpdateNameUsageRequest,CreateNameUsageRequest}.java`, `NameUsageService.java` (drop link handling), `coldp/export/NameUsageColdpWriter.java` (drop link column); `Project.java`?/`ProjectMapper.java`/`dto/ProjectResponse.java`/`dto/UpdateProjectMetadataRequest.java` (add identifierScopes) [wired in Task 3 — column only here]; `Reference.java`/`ReferenceMapper.java`/`dto/ReferenceResponse.java` (add accessed) [wired in Task 4 — column only here]; `frontend/src/api/types.ts` (drop `link` from NameUsage/UpdateUsagePayload); `frontend/src/tree/TaxonDetail.tsx` (drop the Link form field). Tests: extend a name-usage IT.

**Interfaces:** Produces V16 (drops `name_usage.link`; adds `project.identifier_scopes TEXT[]`, `reference.accessed TEXT`). `name_usage.link` gone everywhere.

- [ ] **Step 1: V16 migration** — three `ALTER TABLE`s per the spec.
- [ ] **Step 2: Drop `link` from name_usage** — mirror the committed `coldp_id`-drop refactor (`V14`): remove from POJO getter/setter, `NameUsageMapper` INSERT column+value + UPDATE SET + the SELECT projection, `UpdateNameUsageRequest`/`CreateNameUsageRequest`/`NameUsageResponse`, any `NameUsageService` handling, `NameUsageColdpWriter` (delete the `ColdpTerm.link` mapping), `frontend` `types.ts` (`NameUsage.link`, `UpdateUsagePayload.link`) and the `TaxonDetail` Link `TextInput` + its form field/`toFormValues`/payload. Grep `name_usage`+frontend for `link`/`getLink` → only child-entity/reference links remain.
- [ ] **Step 3: Add the columns to the models WITHOUT wiring** — `reference.accessed` → `Reference.accessed` getter/setter + `ReferenceMapper` SELECT (so it loads; INSERT/UPDATE wired in Task 4); `project.identifier_scopes` → whatever the Project model needs to SELECT it (wired into DTOs in Task 3). Keep this minimal — just enough that V16 columns exist and the SELECTs don't break.
- [ ] **Step 4: Full `mvn verify` + frontend build** — green (schema migration). Commit `refactor(name): drop unused link; V16 adds identifier_scopes + reference.accessed`.

---

### Task 2: Details form — published-in reference + remarks + `alternativeId` in the update

**Files:** `backend/.../name/dto/UpdateNameUsageRequest.java` (add `List<String> alternativeId`); `NameUsageService.update` (write `alternative_id`); `frontend/src/tree/TaxonDetail.tsx`; `frontend/src/api/types.ts` (UpdateUsagePayload.alternativeId). Test: `NameUsageApiIT` (alternativeId round-trips through update); `TaxonDetail.test.tsx`.

**Interfaces:** Produces `UpdateNameUsageRequest.alternativeId` persisted by `updateUsage`; Details form gains `publishedInReferenceId` + `remarks` + carries `alternativeId`.

- [ ] **Step 1: Backend — alternativeId in update.** Add `List<String> alternativeId` to `UpdateNameUsageRequest`; in `NameUsageService.update`, set it on the `NameUsage` before `usages.update(u)` (the mapper already handles `alternative_id` via `StringArrayTypeHandler` — confirm the UPDATE includes it; if not, add it). IT: update a usage with `alternativeId:["ipni:123"]` → GET → present.
- [ ] **Step 2: Frontend Details form.** Add a `publishedInReferenceId` field — an `EntitySelect` loading the project's references (reuse `referenceOptions(pid)` from `NameRelationsTab`), `current` = the usage's `publishedInReferenceId` (label from the loaded set or `#id`). Add a `remarks` `Textarea`. Seed both in `toFormValues`; include `publishedInReferenceId` (Number or undefined) and `remarks` in the update payload (replace the current carry-over of `usage.publishedInReferenceId`/`usage.remarks`). Carry `alternativeId` through unchanged for now (Task 3 populates the scope fields).
- [ ] **Step 3: Tests + commit.** `TaxonDetail.test.tsx`: the published-in picker + remarks render + save PUTs them. `npx vitest run` + `npm run build`; backend IT. Commit `feat(taxon): Details form published-in reference + remarks`.

---

### Task 3: Project identifier scopes + per-scope Details fields

**Files:** `backend/.../project/dto/{ProjectResponse,UpdateProjectMetadataRequest}.java`, `ProjectMapper.java`, `ProjectService.java` (wire `identifierScopes`, mirror `gbifOccurrenceLayer`); `frontend/src/api/projects.ts`; the Project settings page; `frontend/src/tree/TaxonDetail.tsx`. Tests: `ProjectApiIT`; frontend.

**Interfaces:** Produces `ProjectResponse.identifierScopes: string[]` + `UpdateProjectMetadataRequest.identifierScopes`; Details form renders one field per scope, ↔ `alternative_id` CURIEs.

- [ ] **Step 1: Backend wiring** — mirror the committed `gbifOccurrenceLayer` change exactly: `identifier_scopes TEXT[]` (via `StringArrayTypeHandler`) into `ProjectResponse` + `UpdateProjectMetadataRequest` (nullable `List<String>`), `ProjectMapper` SELECT/UPDATE, `ProjectService.update` null-safe carry-over (omit → keep existing). IT: set `identifierScopes:["ipni"]` → GET → present; default null/empty on create.
- [ ] **Step 2: Project settings UI** — a creatable multi-select / `TagsInput` for identifier scopes, seeded from `GET /api/coldp/id-scopes` (fetch the vocab) + free custom entries; included in the metadata update payload.
- [ ] **Step 3: Per-scope Details fields** — in `TaxonDetail`, load the project (already available); for each `project.identifierScopes` scope, render a labelled `TextInput` (label = scope.toUpperCase()). Value = the bare id from the `<scope>:<id>` entry in `usage.alternativeId` (a small `scopedId(alternativeId, scope)` helper). On Save, rebuild `alternativeId` = (existing entries whose scope is NOT a configured/edited scope) + (`<scope>:<value>` for each non-empty scope field) — preserving `col:` etc. Put this in the update payload's `alternativeId`. Unit-test the merge helper.
- [ ] **Step 4: Tests + commit.** Frontend: with a mocked project `identifierScopes:["ipni"]`, the IPNI field renders, prefills from `ipni:123`, and a change PUTs `alternativeId` containing `ipni:456` while preserving a seeded `col:X`. `vitest` + build; backend IT. Commit `feat(project): identifier scopes -> per-scope taxon-form identifier fields`.

---

### Task 4: `reference.accessed` (model + form + import + export)

**Files:** `backend/.../name/Reference.java`, `ReferenceMapper.java` (INSERT/UPDATE), `dto/{ReferenceResponse,CreateReferenceRequest,UpdateReferenceRequest}.java`, `RefMapping.java` (CSL `accessed`, BibTeX `urldate`), `coldp/export/ReferenceColdpWriter.java`; `frontend` reference form + api type. Tests: reference IT + RefMapping test; export.

**Interfaces:** Produces `reference.accessed` end-to-end (create/edit/import/export).

- [ ] **Step 1: Backend** — add `accessed` to `ReferenceMapper` INSERT+UPDATE (SELECT added in Task 1), `ReferenceResponse`, `CreateReferenceRequest`/`UpdateReferenceRequest`, `ReferenceService`. `RefMapping.fromCrossref` (CSL `accessed.date-parts` → ISO) + `.fromBibtex` (`urldate`). `ReferenceColdpWriter`: map `accessed` → `ColdpTerm.accessed` IF that term exists (check `ColdpTerm.RESOURCES.get(Reference)`); else skip + note. IT: create a reference with `accessed` → GET → present; a `RefMappingTest` case for CSL/BibTeX accessed.
- [ ] **Step 2: Frontend** — add an `accessed` field (text/date) to the reference create/edit form + the `Reference` api type. Test + build.
- [ ] **Step 3: Full verify + commit** `feat(reference): accessed (CSL/urldate) field`.

---

### Task 5: References tab — set references + add web-URL reference

**Files:** Create `backend/.../name/WebPageClient.java`, `dto/WebReferenceRequest.java`, `dto/ReferenceIdsRequest.java`; modify `NameUsageService.java` (setReferences + addWebReference) + `NameUsageController.java` (2 endpoints) + `NameUsageMapper.java` (`updateReferenceIds` CAS if needed); `frontend/src/api/usages.ts` + a new `child/ReferencesTab.tsx` + `TaxonDetail.tsx` (add the tab). Tests: ITs + frontend.

**Interfaces:** Produces `PUT /api/projects/{pid}/usages/{uid}/references` (`{referenceIds:int[], version}`) and `POST /api/projects/{pid}/usages/{uid}/web-reference` (`{url}`) → creates a webpage reference + links it.

- [ ] **Step 1: Backend references PUT** — `ReferenceIdsRequest(List<Integer> referenceIds, Integer version)`; `NameUsageService.setReferences(userId, pid, id, req)` = editor guard + validate each id is a project reference + CAS-update `name_usage.reference_id` (add `NameUsageMapper.updateReferenceIds(projectId,id,List<Integer>(StringArray? no — INTEGER[]; use the array type handler),modifiedBy,version)`; boxed `Integer version` → 409 on stale) + audit + `ValidationEvent`. Controller `PUT …/references`. IT: set → replace → 409; an id from another project → 400/404.
- [ ] **Step 2: `WebPageClient` (SSRF-guarded)** — `@Component`, `RestClient.builder()` static + connect/read timeouts. `fetchTitle(String url)`: reject non-http(s); resolve the host (`InetAddress.getAllByName`) and reject loopback/link-local/site-local/any-local/multicast; GET the page (cap body ~512KB); extract `<title>…</title>` (case-insensitive, decode HTML entities); return the title or null. Throw a 400 `ResponseStatusException` for a disallowed/invalid URL. Unit test: a `http://127.0.0.1/x` and `http://169.254.169.254/` are rejected (no fetch).
- [ ] **Step 3: web-reference POST** — `WebReferenceRequest(String url)`; `NameUsageService.addWebReference(userId, pid, usageId, url)` = editor guard; `title = webPageClient.fetchTitle(url)` (fallback to url); create a `Reference` (`type="webpage"`, `title`, `link`=url, `accessed`=today ISO, `author`= the URL host as literal when parseable) via the reference-create path (allocate id); append its id to the usage's `reference_id[]` (reuse setReferences' update); audit; return the updated reference list (or the new reference). Controller `POST …/web-reference`. IT: `@MockitoBean WebPageClient` returns a title → POST → a `type=webpage` reference exists with that title + `accessed` + is in `reference_id[]`.
- [ ] **Step 4: Frontend References tab** — `api/usages.ts`: `listUsageReferences`(resolve `reference_id[]` → references; or GET the usage + map ids to references via `listReferences`), `setUsageReferences(pid,uid,ids,version)`, `addWebReference(pid,uid,url)`. `child/ReferencesTab.tsx`: a table of the usage's references (citation or webpage title + a "web" badge + external link), an **Add reference** `EntitySelect` (append id → `setUsageReferences`), an **Add web URL** input+button (→ `addWebReference`), and remove (→ `setUsageReferences` without that id). Add a **References** tab to `TaxonDetail` (any usage). Invalidate `['usage',pid,uid]`.
- [ ] **Step 5: Tests + full gates + commit.** Frontend (MSW): list + add existing + add web URL + remove. Backend `mvn verify` + `vitest` + build. Commit `feat(taxon): References tab (taxonomic refs + web-URL webpage references)`.

---

## Self-Review notes
- Spec coverage: drop-link/V16 = T1; publishedIn+remarks = T2; identifier scopes + per-scope fields = T3; reference.accessed = T4; References tab + web-reference = T5.
- Reuse: T1 mirrors the `coldp_id` drop; T2/T3 backend mirror `setIdentifiers`/`gbifOccurrenceLayer`; T5's `WebPageClient` mirrors `CrossrefClient`; the References tab reuses `EntitySelect`.
- Type consistency: `alternativeId` on `UpdateNameUsageRequest` (T2) is populated by the scope-field merge (T3); `reference_id[]` set path (T5 step 1) is reused by web-reference (T5 step 3).
- Security: T5 step 2 is the SSRF surface — the guards + the `@MockitoBean` test are mandatory; never a real network fetch in tests.
- Verify during T5: `name_usage.reference_id` is `INTEGER[]` — use the integer-array binding (not the String array handler); confirm the mapper handler.
