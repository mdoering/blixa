# Reference PDF hosting + RIS import — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** (A) import references from RIS (Zotero/EndNote/Mendeley), and (B) upload/host one PDF per reference (dedicated `pdf` field, `link` stays the user's).

**Architecture:** RIS mirrors the existing BibTeX import (`RefMapping.from…` → `ReferenceImportService` → endpoint → import UI). PDF hosting adds a `reference.pdf` column, a `PdfService` (store/serve from `coldp.pdf.dir`), attach/remove/serve endpoints, and a `ReferenceForm` control; the served URL is public and env-portable (backend serves `/pdf` in dev/compose, Apache alias in prod).

**Tech Stack:** Spring Boot 4.1 / Java 25 / Postgres 17 / MyBatis / Flyway; React 18 / Mantine 7 / Vitest + MSW.

## Global Constraints

- Build/test with **JDK 25 via `current`**: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/current ./mvnw ...`.
- **Run `clean verify` (not incremental) when a record's arity/component types change.**
- Commit directly to `main`; one commit per task after tests pass.
- Spec: `docs/superpowers/specs/2026-07-11-reference-pdf-and-ris-design.md`. Next migration is **V20**.
- **Do NOT touch `./Jenkinsfile`** (the deploy CI is being authored separately).
- **Reuse (committed):** the BibTeX import path `name/{RefMapping.fromBibtex, ReferenceImportService.importBibtex, ReferenceController (import-bibtex endpoint), dto/BibtexRequest}` + the frontend BibTeX import control (grep `references/` for it); the multipart plumbing from ColDP import (`api()`'s `formData` branch, `spring.servlet.multipart` config, `ImportRunService`'s size-cap/streaming patterns); `ReferenceService` (create, opt-locked update, audit + `ValidationEvent`); the export-file download endpoint pattern (`coldp/export/ExportRunController` `…/file`) as a serve mirror.

---

### Task 1: RIS import (backend + frontend)

**Files:** Modify `name/RefMapping.java` (add `fromRis`), `name/ReferenceImportService.java` (add `importRis`), `name/ReferenceController.java` (add the endpoint), create `name/dto/RisRequest.java`; frontend the reference-import UI (grep the BibTeX import component) + `api/references.ts`. Tests: `name/RefMappingRisTest.java`, `name/ReferenceImportRisIT.java`, the frontend import test.

- [ ] **Step 1: `RefMapping.fromRis(String ris) : List<CreateReferenceRequest>`** — hand-rolled RIS parser. Lines are `^([A-Z0-9]{2})  - (.*)$` (tag, two spaces, dash, space, value); tolerate CRLF + blank lines. A record spans `TY  -` … `ER  -`. Accumulate tags into a record; on `ER` flush a `CreateReferenceRequest` (read `CreateReferenceRequest`'s constructor/fields — mirror how `fromBibtex` builds it). Tag → field map (per the spec): `TY`→type (RIS-code→CSL: JOUR→article-journal, BOOK→book, CHAP→chapter, CONF/CPAPER→paper-conference, THES→thesis, RPRT→report, ELEC/WEB→webpage, default `document`); `AU`/`A1`→author (join multiple `; `); `A2`/`ED`→editor; `TI`/`T1`→title; `T2`/`JO`/`JF`/`JA`→containerTitle; `PY`/`Y1`→issued (strip to year if `YYYY/...`); `VL`→volume; `IS`→issue; `SP`+`EP`→page (`SP–EP`, or just SP); `DO`→doi; `SN`→isbn if it matches an ISBN-ish pattern else issn; `PB`→publisher; `UR`/`L1`→link; `ID`→an `alternativeID` (e.g. `ris:<id>`); unknown tags ignored. Multiple records supported.
- [ ] **Step 2: unit test `RefMappingRisTest`** — a journal-article record, a book record, a 2-record file, an unknown-tag/blank-line case, a multi-author join → assert the mapped fields + type + count. (Grab a real Zotero/EndNote-style RIS snippet as one fixture.)
- [ ] **Step 3: `ReferenceImportService.importRis(int userId, int projectId, String ris)`** — `RefMapping.fromRis` → `references.create(userId, projectId, req)` per record → return the created `List<Reference>` (mirror `importBibtex` exactly).
- [ ] **Step 4: endpoint** — `name/dto/RisRequest(String ris)`; `ReferenceController` `@PostMapping("/api/projects/{pid}/references/import-ris")` → `importRis` → `List<ReferenceResponse>` (mirror the import-bibtex mapping + status). `ReferenceImportRisIT`: POST a 2-record RIS → 2 references created with the asserted fields.
- [ ] **Step 5: frontend** — `api/references.ts` `importRisReferences(pid, ris)`; add a **RIS** tab/option to the reference-import UI beside BibTeX (a `.ris` FileInput that reads the text + a paste `Textarea` → `importRisReferences` → on success refetch the references list + show the created count). Mirror the BibTeX control's structure + its test. Frontend gates green.
- [ ] **Step 6: clean verify + gates + commit** `feat(refs): RIS reference import (Zotero/EndNote/Mendeley)`.

---

### Task 2: Reference PDF hosting — backend

**Files:** Create `db/migration/V20__reference_pdf.sql`, `name/PdfService.java`, `name/PdfController.java` (or add to `ReferenceController`); modify `name/Reference.java` (+`pdf`), `ReferenceMapper.java` (select/insert/update `pdf` + a `updatePdf`), `dto/ReferenceResponse.java` (+`pdfUrl`), `name/ReferenceService.java` (attachPdf/removePdf), `ReferenceColdpWriter.java` (export link), `application.yml` (coldp.pdf.*), `SecurityConfig.java` (permit `/pdf/**`). Test: `name/PdfServiceTest.java`, `name/ReferencePdfIT.java`.

- [ ] **Step 1: V20 migration** — `ALTER TABLE reference ADD COLUMN pdf TEXT;`.
- [ ] **Step 2: config** — `application.yml`: `coldp.pdf.dir: ${COLDP_PDF_DIR:${java.io.tmpdir}/coldp-pdfs}`, `coldp.pdf.max-bytes: 33554432`, `coldp.pdf.base-url: ${COLDP_PDF_BASE_URL:/pdf}`. Add `COLDP_PDF_BASE_URL` to the deploy env templates (`deploy/blixa.env.example`) with a commented absolute example.
- [ ] **Step 3: `Reference.pdf` wiring** — add the field + getter/setter; `ReferenceMapper` SELECTs it (all reference reads), and add `int updatePdf(@Param("projectId") int, @Param("id") int, @Param("pdf") String, @Param("modifiedBy") int, @Param("version") Integer)` (opt-locked, `version=version+1, modified=now()`). `ReferenceResponse` gains `pdfUrl` (`baseUrl + "/" + pdf` when `pdf != null`, else null — pass the base url into `ReferenceResponse.of` or compute in the service).
- [ ] **Step 4: `PdfService`** — `String store(int projectId, int refId, MultipartFile file)`: reject if `file.getSize() > maxBytes` (413) or not a PDF (content-type not `application/pdf` AND/OR first bytes not `%PDF-` → 400); generate `p{projectId}-r{refId}-{shortUuid}.pdf`; `Files.write` into `coldp.pdf.dir` (created in ctor); return the filename. `void delete(String filename)` best-effort. `Path resolve(String filename)`: `dir.resolve(filename).normalize()`, throw 400 if `!startsWith(dir)` (path-traversal), 404 if not a regular file. Inject `@Value` coldp.pdf.dir/max-bytes.
- [ ] **Step 5: `ReferenceService.attachPdf/removePdf`** — `attachPdf(userId, pid, refId, MultipartFile)`: `requireEditor`; load ref (opt-lock read); if it already has a pdf, `pdfService.delete(oldPdf)`; `filename = pdfService.store(...)`; `references.updatePdf(pid, refId, filename, userId, version)` (0 rows → 409); audit + `ValidationEvent`; return the updated `ReferenceResponse`. `removePdf(...)`: clear the column (`updatePdf(...,null,...)`) + `pdfService.delete(oldPdf)`.
- [ ] **Step 6: endpoints + security** — `POST /api/projects/{pid}/references/{id}/pdf` (multipart `@RequestPart("file")`) → `ReferenceResponse`; `DELETE /api/projects/{pid}/references/{id}/pdf` → `ReferenceResponse`; `GET /pdf/{filename}` → stream `pdfService.resolve(filename)` with `Content-Type: application/pdf` + `Content-Disposition: inline; filename="…"` (mirror the export `…/file` streaming). `SecurityConfig`: add `/pdf/**` to `permitAll` (public read; the upload/delete stay under `/api/**` authenticated). Grep the current `SecurityConfig` permit list + add there.
- [ ] **Step 7: ColDP export link** — in `ReferenceColdpWriter`, the `link` column value = `pdf != null && (ref.getLink() == null || blank) ? (coldp.pdf.base-url + "/" + pdf) : ref.getLink()`. Inject the base url into the writer (or precompute per row). (So a hosted PDF is citable in the archive without clobbering a user link.)
- [ ] **Step 8: tests + clean verify + commit** — `PdfServiceTest`: a non-PDF (bad magic) rejected 400; oversize rejected 413; `resolve("../x")` blocked. `ReferencePdfIT`: `attachPdf` sets `pdf` + `pdfUrl` in the response; `GET /pdf/{file}` returns the bytes publicly (no auth); `removePdf` clears it + the file is gone; the ColDP export writes the PDF URL to `link` when link is blank and the user's link when set. Clean verify. Commit `feat(refs): reference PDF upload + hosting (dedicated pdf field, public /pdf serve, export link)`.

---

### Task 3: Reference PDF hosting — frontend

**Files:** Modify the reference form (grep `references/ReferenceForm*`), `frontend/src/api/references.ts`, `frontend/vite.config.ts` (proxy `/pdf`), `frontend/nginx.conf` (proxy `/pdf` to backend). Test: the reference-form test.

- [ ] **Step 1: proxy `/pdf` to the backend** — `vite.config.ts`: add `/pdf` to the dev proxy targets (alongside `/api`,`/oauth2`,`/login`) → `:8080`. `frontend/nginx.conf` (the compose frontend): add `/pdf` to the `location ~ ^/(api|login|oauth2)…` proxy prefix set (so the compose serves the PDF via the backend). (Prod Apache already has the `/pdf` alias — no change.)
- [ ] **Step 2: `api/references.ts`** — `attachReferencePdf(pid, id, file)` (POST multipart via `api()`'s `formData` branch), `removeReferencePdf(pid, id)` (DELETE). The `Reference`/`ReferenceResponse` TS type gains `pdfUrl: string | null`.
- [ ] **Step 3: `ReferenceForm` PDF control** — a **PDF** section: when `pdfUrl` is null, a `FileInput` (`accept="application/pdf"`) + an Attach action → `attachReferencePdf` → refetch/update the form; when a PDF is attached, show a **"View PDF"** `Anchor` (href `pdfUrl`, target _blank) + a **Remove** button → `removeReferencePdf`. Shown alongside (not replacing) the `link` text field. Wire into the existing create/edit form; invalidate the references list/detail queries on change.
- [ ] **Step 4: test + gates + commit** — reference-form test (MSW): upload a PDF → the View/Remove controls appear (mock the attach returning a `pdfUrl`); Remove → back to the FileInput. `vitest` + `tsc` + build. Commit `feat(refs): reference PDF control in the references editor (upload / view / remove)`.

---

## Self-Review notes
- **Spec coverage:** RIS import = T1; PDF backend (model/config/service/endpoints/security/export) = T2; PDF frontend = T3.
- **Reuse:** RIS mirrors BibTeX end-to-end; PDF upload reuses the `api()` `formData` branch + multipart config; the serve endpoint mirrors the export-file download; `attachPdf`/`removePdf` mirror `ReferenceService`'s opt-locked update + audit/validation pattern.
- **Type consistency:** `reference.pdf` (filename) ↔ `ReferenceResponse.pdfUrl` (base + filename) ↔ frontend `pdfUrl`; `coldp.pdf.base-url` used by both the response and the export writer; RIS `CreateReferenceRequest` is the same shape BibTeX produces.
- **Env portability:** `/pdf` served by the backend (dev/compose, via the Vite/nginx proxy) and by Apache's alias (prod) — same relative URL; `coldp.pdf.base-url` set absolute only for export links in deploy.
- **Untouched:** `./Jenkinsfile`.
