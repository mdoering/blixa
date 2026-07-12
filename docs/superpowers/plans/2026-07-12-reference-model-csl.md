# Reference Model Overhaul (structured CSL + generated citations) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** References become structured CSL data (typed via `CSLType`, `CslName[]` authors/editors, short container title), with the **citation generated** by CLB's CSL engine in a **project-selectable style** (default APA) and shown read-only — no dual-edit drift.

**Architecture:** Reuse CLB's `life.catalogue.common.csl.CslUtil` / `CslFormatter` (bundled `apa/…/taxon` styles on `citeproc-java`, already on the classpath). Store `author`/`editor` as JSONB `CslName[]`. A `ReferenceCitationService` builds a `CslData` from a reference and renders it per the project's style. **No data migration** — fresh installs only.

**Tech Stack:** Backend Spring Boot 4.1 / Java 25 / Postgres 17 / MyBatis / Jackson / Testcontainers ITs. Frontend React + TS + Mantine 7 + TanStack Query + Vitest + MSW. CLB `org.catalogueoflife:api` (life.catalogue.*).

## Global Constraints

- **No data migration / backfill** — the owner wipes and reinstalls, so migrations may drop+recreate the `author`/`editor` columns with no concern for existing rows.
- CLB CSL API (confirmed): `CslUtil.buildCitation(CslData)` → APA plain text; **`new CslFormatter(CslFormatter.STYLE.<X>, CslFormatter.FORMAT.TEXT).cite(CslData)`** → plain-text citation in style X; `STYLE` = `{APA, CSE, IEEE, MLA, CHICAGO, HARVARD, EJT, TAXON}`; `CslFormatter.cite` is `synchronized` (cache one formatter per style). `CslUtil.toColdpString(CslName[])` → ColDP author string. Models: `life.catalogue.api.model.{CslData, CslName, CslDate, CSLType}`. `CslName{family, given, droppingParticle, nonDroppingParticle, suffix, isInstitution, literal}`.
- JSONB list handler: mirror `backend/.../project/IdentifierScopeListTypeHandler.java` (Jackson (de)serialize a `List<T>` to a `jsonb` column via `PGobject`).
- Build with JDK 25: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw ...`. Record-arity changes → `./mvnw -q clean test-compile`.
- Frontend gates: `cd frontend && npx tsc -b && npx vitest run`.
- Commit to `main`. DO NOT stage `todo.md`/`todo-next.md`/`blixa*.svg`/`application-dev.yml`/`.gitignore`.
- Latest migration is `V23__join_request.sql` → the first new migration here is **V24**.

---

### Task 1: Backend — structured `CslName` authors/editors (model, JSONB, DTOs)

**Files:**
- Create: `backend/src/main/resources/db/migration/V24__reference_csl.sql`
- Create: `backend/.../name/CslNameListTypeHandler.java`
- Modify: `backend/.../name/Reference.java` (author/editor → `List<CslName>`; add `containerTitleShort`, `citationManual`)
- Modify: `backend/.../name/ReferenceMapper.java` (insert/update/result map for JSONB author/editor + new cols)
- Modify: `backend/.../name/dto/{CreateReferenceRequest,UpdateReferenceRequest,ReferenceResponse}.java` (author/editor as `List<CslName>`, add `containerTitleShort`, `citationManual`)
- Modify: `backend/.../name/ReferenceService.java` (pass the new fields through)
- Test: `backend/src/test/java/org/catalogueoflife/editor/name/ReferenceCslIT.java`

**Interfaces:**
- Produces: `Reference.getAuthor()/getEditor()` → `List<CslName>` (CLB `life.catalogue.api.model.CslName`); reference columns `author jsonb`, `editor jsonb`, `container_title_short text`, `citation_manual boolean`.
- Consumes: `IdentifierScopeListTypeHandler` as the JSONB-handler template; Jackson `ObjectMapper` (Spring bean).

- [ ] **Step 1: Write the failing IT** — `ReferenceCslIT`: as owner/editor create a reference with `author: [{family:"Bánki", given:"Olaf"}, {family:"Döring", given:"Markus"}]`, `type:"article-journal"`, title, containerTitle, containerTitleShort, issued year; GET it back and assert the two `CslName`s round-trip (family/given), `containerTitleShort` present. (MockMvc + AbstractPostgresIT; mirror an existing reference IT.)
- [ ] **Step 2: Run it, verify it fails** — `./mvnw -q -Dtest=ReferenceCslIT test` → FAIL.
- [ ] **Step 3: Migration V24** — drop the old `author`/`editor` TEXT columns and add `author jsonb`, `editor jsonb`, `container_title_short text`, `citation_manual boolean NOT NULL DEFAULT false` on `reference`. (No backfill.) Comment that authors/editors are CSL name arrays.
- [ ] **Step 4: `CslNameListTypeHandler`** — a `BaseTypeHandler<List<CslName>>` mirroring `IdentifierScopeListTypeHandler`: serialize with Jackson to a `PGobject(type="jsonb")`; deserialize the `jsonb` string to `List<CslName>` (Jackson `readValue` with a `TypeReference<List<CslName>>`). Handle null/empty.
- [ ] **Step 5: Model + DTOs** — `Reference.author/editor` → `List<CslName>`; add `containerTitleShort`, `citationManual`. Update `CreateReferenceRequest`/`UpdateReferenceRequest`/`ReferenceResponse` accordingly (they currently carry `String author/editor`). Update every construction site (grep — `RefMapping`, `ReferenceService`, ITs) so it compiles; import mappers set author/editor to `List.of()` for now where they built strings (Task 4 fixes them properly).
- [ ] **Step 6: Mapper** — `ReferenceMapper` insert/update bind `author`/`editor` via `#{author,typeHandler=org.catalogueoflife.editor.name.CslNameListTypeHandler}` (+ the same on the result map), and the two new columns. Keep `alternative_id`/`version` handling intact.
- [ ] **Step 7: Run the IT green + clean compile** — `./mvnw -q clean test-compile` then `./mvnw -q -Dtest=ReferenceCslIT test`. Fix other compile breaks from the DTO change (grep call sites).
- [ ] **Step 8: Commit** — `feat(reference): structured CslName authors/editors + short container title (JSONB)`.

---

### Task 2: Backend — `CSLType` validation + vocab exposure (R1)

**Files:**
- Modify: `backend/.../name/ReferenceService.java` (validate `type`)
- Modify: a vocab controller (e.g. `coldp/VocabController` or wherever `/api/coldp/vocab` is served) OR add `GET /api/vocab/csl-type`
- Test: extend `ReferenceCslIT` (bad type → 400) + a vocab-endpoint assertion

**Interfaces:**
- Consumes: `life.catalogue.api.model.CSLType` (confirm its wire form — likely a `@JsonValue`/value string like `"article-journal"`; grep the enum).
- Produces: reject unknown `type` with 400; a JSON list of valid CSL type wire values for the frontend Select.

- [ ] **Step 1: Failing test** — create a reference with `type:"not-a-type"` → expect 400; and the vocab endpoint returns a non-empty list including `article-journal`.
- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Validate** — in `ReferenceService.create/update`, tolerantly parse `type` into `CSLType` (helper like `Licenses.parse`: match the enum's wire value case-insensitively; null/blank allowed → null; unknown → `ResponseStatusException(BAD_REQUEST, "unknown reference type")`). Store the canonical wire value.
- [ ] **Step 4: Vocab** — expose the `CSLType` values (their wire strings) at `GET /api/vocab/csl-type` (or add to the existing `/api/coldp/vocab` payload). Any authenticated member.
- [ ] **Step 5: Run green.**
- [ ] **Step 6: Commit** — `feat(reference): validate reference type against CSLType + expose the vocab`.

---

### Task 3: Backend — generated citation + project CSL style (R4)

**Files:**
- Create: `backend/.../name/ReferenceCitationService.java`
- Create: `backend/src/main/resources/db/migration/V25__project_csl_style.sql`
- Modify: `backend/.../project/{Project,ProjectMapper}.java` + metadata DTO/service (`cslStyle`)
- Modify: `backend/.../name/ReferenceService.java` (regenerate on create/update unless `citationManual`)
- Modify: `backend/.../project/ProjectService.java` (on `cslStyle` change → recompute the project's generated citations)
- Test: `backend/src/test/java/org/catalogueoflife/editor/name/ReferenceCitationIT.java`

**Interfaces:**
- Consumes: `CslFormatter`/`CslUtil` (per Global Constraints); `Reference` (Task 1); `project.csl_style`.
- Produces: `ReferenceCitationService.render(Reference ref, String cslStyle) -> String`; regeneration wired into reference writes + project-style change.

- [ ] **Step 1: Failing IT** — `ReferenceCitationIT`: create a reference (structured authors + title + journal + year, `citationManual=false`) in a project with default style → the stored/returned `citation` equals CLB's APA rendering of that data (assert it contains the authors + year in APA order, non-empty; you can assert equality against `new CslFormatter(STYLE.APA, FORMAT.TEXT).cite(<same CslData>)`). Then set `project.cslStyle="harvard"` → the citation regenerates (differs from APA / matches Harvard). A `citationManual=true` reference keeps its supplied string unchanged on update.
- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: `ReferenceCitationService`** — build a `CslData` from a `Reference` (type→`CSLType`, author/editor `CslName[]`, `issued` `CslDate` from the year, title, containerTitle, containerTitleShort→`containerTitleShort`, volume/issue/page, publisher, DOI, isbn/issn); render with a **cached** `CslFormatter` per `STYLE` (map the project's `cslStyle` string, e.g. `"apa"`, to `CslFormatter.STYLE.APA`; default APA on unknown). Return plain text; on any citeproc failure, fall back to a simple `author + " (" + year + ") " + title` string (never throw out of a save).
- [ ] **Step 4: Project `csl_style`** — V25 adds `project.csl_style text not null default 'apa'`; `Project` + `ProjectMapper` + metadata read/update carry `cslStyle` (validate against the STYLE set on write).
- [ ] **Step 5: Regenerate-on-write** — in `ReferenceService.create/update`: if `citationManual` is false, set `citation = citationService.render(ref, project.cslStyle)` before persisting. If a caller supplies a citation AND no structured content, set `citationManual=true` and keep it.
- [ ] **Step 6: Recompute-on-style-change** — in `ProjectService.updateMetadata`, when `cslStyle` changes, re-render + persist the `citation` of every non-manual reference in the project (a bulk loop; fine synchronously for now — note if a project has very many references this could be async later).
- [ ] **Step 7: Run green** (`clean test-compile` if a record arity changed).
- [ ] **Step 8: Commit** — `feat(reference): generate citations via CLB CSL engine in the project's style`.

---

### Task 4: Backend — import + ColDP export alignment (Phase 2)

**Files:**
- Modify: `backend/.../name/RefMapping.java` (`fromCrossref/fromBibtex/fromRis/fromDatacite` → `CslName[]` authors/editors + `type` + let citation be generated)
- Modify: `backend/.../coldp/export/ReferenceColdpWriter.java` (+ the ColDP reader mapping in `coldp/imprt/…`) — structured authors
- Test: extend `RefMapping*Test` + the ColDP round-trip IT (`ImportExportRoundTripIT`)

**Interfaces:**
- Consumes: `CslName`, `CslUtil.toColdpString(CslName[])` (for the ColDP author string form), `ReferenceCitationService`.
- Produces: imports yield structured authors + a generated citation; ColDP export/import preserve structured authors.

- [ ] **Step 1: Failing tests** — (a) `RefMapping.fromBibtex` on the double-bracket example now returns `author` as `List<CslName>` (`[{family:"Bánki", given:"Olaf"}, …]`, brace-free) rather than a joined string; (b) the ColDP round-trip IT: a reference with structured authors exports and re-imports with the same `CslName`s.
- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: `RefMapping.*`** — each importer parses names into `CslName` (family/given split on the first comma; institution/single-token → `literal`); reuse a shared `List<CslName> parseNames(String rawAndSeparated)` helper. Set `type` from the source (Crossref/CSL `type`, BibTeX entry type→CSL, RIS `TY`→CSL, DataCite `types.resourceTypeGeneral`→CSL; a small mapping, unknowns → null or `article-journal`). Leave `citation` to be generated by `ReferenceCitationService` on create (importers stop hand-building it, except keep a manual fallback for a reference with no parseable structured content → `citationManual=true`).
- [ ] **Step 4: ColDP export/import** — `ReferenceColdpWriter` emits the author/editor in ColDP's expected form (use `CslUtil.toColdpString(CslName[])` for the `author`/`editor` columns, OR the structured JSON if the reader round-trips it — confirm against `org.catalogueoflife:reader` ColdpTerm fields); the importer parses them back into `CslName[]`. Keep the round-trip lossless for family/given.
- [ ] **Step 5: Run green.**
- [ ] **Step 6: Commit** — `feat(reference): imports + ColDP export use structured CSL names`.

---

### Task 5: Frontend — CSLType Select + CslName list editor + short title (R1/R2/R3)

**Files:**
- Modify: `frontend/src/references/ReferenceForm.tsx`
- Create: `frontend/src/references/CslNameEditor.tsx` (+ test)
- Modify: `frontend/src/api/references.ts` + `frontend/src/api/types.ts` (`CslName` type; author/editor as `CslName[]`)
- Modify: `frontend/src/references/ReferenceForm.test.tsx`

**Interfaces:**
- Consumes: `GET /api/vocab/csl-type` (Task 2); the reference DTOs now carry `author/editor: CslName[]`, `containerTitleShort`, `citationManual`.
- Produces: `CslNameEditor` (`value: CslName[]`, `onChange`); Type `<Select>`.

- [ ] **Step 1: Failing test** — `CslNameEditor.test.tsx`: renders a row per name (family/given inputs), an "Add author" button appends a blank row, remove drops one, an "institution" toggle switches a row to a single literal input; `onChange` emits the updated `CslName[]`.
- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: `CslNameEditor`** — a compact editor for `CslName[]`: each row = family + given inputs (+ optional particle/suffix behind a small "more" affordance) + a remove button; an institution toggle per row (renders a single `literal` input instead); an "Add" button. Controlled via `value`/`onChange`.
- [ ] **Step 4: Wire into `ReferenceForm`** — replace the Author and Editor `<TextInput>`s with `<CslNameEditor>` (each on its own line, per the earlier D1 layout); make **Type** a `<Select>` (searchable) fed by `GET /api/vocab/csl-type`; add a "Container title (short)" `<TextInput>`. Map form values to/from `CslName[]`.
- [ ] **Step 5: Types + api** — add `CslName` to `types.ts`; `Reference`/payloads carry `author/editor: CslName[]`, `containerTitleShort`, `citationManual`; add `getCslTypes()`.
- [ ] **Step 6: Run green** (`tsc -b` + full vitest). Fix other consumers of `reference.author` (e.g. `ReferencesPage`'s author column — render `authorsToString(author)` = family names joined, a small helper).
- [ ] **Step 7: Commit** — `feat(references): CSLType select + structured author/editor editor + short title`.

---

### Task 6: Frontend — generated citation (read-only + preview) + project style picker (R4)

**Files:**
- Modify: `frontend/src/references/ReferenceForm.tsx` (citation read-only + preview)
- Modify: `frontend/src/projects/ProjectMetadataPage.tsx` (citation-style Select in Settings)
- Modify: `frontend/src/api/{references,projects}.ts` + tests

**Interfaces:**
- Consumes: `citationManual` on the reference; `cslStyle` on the project metadata; optionally a `POST /api/projects/{pid}/references/preview-citation` (add in Task 3/here) for a live preview.
- Produces: read-only citation with preview; project citation-style picker.

- [ ] **Step 1: Failing tests** — `ReferenceForm.test.tsx`: for a structured reference (`citationManual:false`), the Citation field is read-only and shows the generated string (from the loaded reference or a preview call); for a `citationManual:true` reference it's editable. `ProjectMetadataPage.test.tsx`: the Settings section has a "Citation style" Select bound to `cslStyle` that saves via `updateMetadata`.
- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Citation UI** — in `ReferenceForm`, when `!citationManual`, render the Citation as read-only text (the stored generated value) with a note "generated from the fields above in the project's citation style"; optionally a debounced preview via a `POST .../references/preview-citation` (add the endpoint in Task 3 if you take the live-preview route — otherwise show the last-saved citation and regenerate on save). When `citationManual`, keep the editable `<TextInput>`.
- [ ] **Step 4: Style picker** — add a "Citation style" `<Select>` (APA default; options = the bundled STYLE set) to the project **Settings** section (from the earlier B1 reorg) bound to `cslStyle` → `updateMetadata`. Note that changing it regenerates citations (backend Task 3).
- [ ] **Step 5: Run green** (`tsc -b` + full vitest).
- [ ] **Step 6: Commit** — `feat(references): read-only generated citation + project citation-style picker`.

---

## Final verification (after all tasks)

- [ ] Backend full suite: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw -q test` → green.
- [ ] Frontend gates: `cd frontend && npx tsc -b && npx vitest run` → clean + green.
- [ ] Manual smoke (`docker compose up`, fresh DB): create a reference with two structured authors + a journal + year → the citation renders in APA; switch the project citation style → citations re-render; a DOI/BibTeX import yields structured authors; a citation-only paste stays editable.

## Notes carried from the spec

- No data migration (fresh installs) — migrations may drop+recreate columns freely.
- Citation is generated + read-only for structured references; `citation_manual` protects hand-typed citations.
- Project-level style only (default APA); only the bundled CLB styles are selectable.
- Reuse CLB `CslUtil`/`CslFormatter` — do not vendor citeproc.
