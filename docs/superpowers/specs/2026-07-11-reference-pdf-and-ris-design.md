# Reference PDF hosting + RIS import — design

**Status:** approved (brainstorm) · **Date:** 2026-07-11

## Context & goal

Two independent enhancements to the References editor:

1. **RIS import** — import references from an RIS file/paste. RIS is the common interchange format that
   Zotero, EndNote and Mendeley all export, so this one parser covers all three. Extends the existing
   reference import (DOI/Crossref, BibTeX).
2. **Reference PDF hosting** — upload one PDF per reference; the editor stores the binary (PDFs are not
   part of ColDP) and serves it at a stable URL. A **dedicated `pdf` field** holds it; the reference's
   `link` stays the user's own value.

## Feature A — RIS import

- **`RefMapping.fromRis(String ris) : List<CreateReferenceRequest>`** — a hand-rolled RIS parser. RIS is
  line-based tagged text: `XY  - value` (two-letter tag, two spaces, dash, space). A record starts at
  `TY  - <type>` and ends at `ER  -`. Map the common tags → our `CreateReferenceRequest` fields:
  `TY`→`type` (RIS type code → CSL type: `JOUR`→`article-journal`, `BOOK`→`book`, `CHAP`→`chapter`,
  `CONF`/`CPAPER`→`paper-conference`, `THES`→`thesis`, `RPRT`→`report`, `ELEC`/`WEB`→`webpage`, else
  `document`); `AU`/`A1`/`A2`(editor)→`author`/`editor` (join multiple with `; `); `TI`/`T1`→`title`;
  `T2`/`JO`/`JF`/`JA`→`containerTitle`; `PY`/`Y1`→`issued` (year); `VL`→`volume`; `IS`→`issue`;
  `SP`+`EP`→`page` (`SP–EP`); `DO`→`doi`; `SN`→`isbn`/`issn` (ISBN if it looks like one, else issn);
  `PB`→`publisher`; `UR`/`L1`→`link`; `AB`→(remarks or drop); `ID`→an `alternativeID`. Multiple
  records per file. Robust to unknown tags (ignore), blank lines, CRLF.
- **`ReferenceImportService.importRis(userId, projectId, ris)`** — parse → bulk `ReferenceService.create`
  each (mirror `importBibtex`).
- **`POST /api/projects/{pid}/references/import-ris`** (body `record RisRequest(String ris)`) → the created
  `ReferenceResponse`s (mirror the BibTeX endpoint).
- **Frontend:** add a **RIS** option to the reference-import UI (a `.ris` file upload / paste textarea →
  `importRis` → bulk-created refs appear), mirroring the existing BibTeX import control.

## Feature B — reference PDF hosting

### Data model
- **V20 migration:** `ALTER TABLE reference ADD COLUMN pdf TEXT;` — holds the stored PDF's generated
  **filename** (null = no PDF). Not the URL (the URL is derived from the base + filename).

### Config (application.yml)
- `coldp.pdf.dir` — storage dir (env `COLDP_PDF_DIR`, already provisioned in deploy). Default under the
  temp/data dir; created on startup.
- `coldp.pdf.max-bytes` — upload size cap (default e.g. 33554432 = 32 MB).
- `coldp.pdf.base-url` — the public base the served URL is built from (default **`/pdf`** = same-origin;
  a deploy sets an absolute `https://<host>/pdf` so the URL is absolute in ColDP exports).

### Backend
- **`PdfService`**: `store(projectId, refId, MultipartFile) : String filename` — validate it's a PDF
  (content-type `application/pdf` AND the `%PDF-` magic bytes) and within `max-bytes`; generate a
  non-guessable filename (`p{projectId}-r{refId}-{shortUuid}.pdf`); write to `coldp.pdf.dir`; delete any
  prior file for that reference; return the filename. `delete(filename)` — best-effort remove.
  `resolve(filename) : Path` — resolve within `coldp.pdf.dir`, **path-traversal guarded**
  (`normalize().startsWith(dir)`), for serving.
- **`ReferenceService`** gains: `attachPdf(userId, pid, refId, MultipartFile)` (owner/editor; store,
  set `reference.pdf=filename`, opt-locked update, audit + validation event) and `removePdf(...)`
  (clear the column + delete the file).
- **Endpoints** (`ReferenceController`):
  - `POST /api/projects/{pid}/references/{id}/pdf` — `multipart/form-data` (`file`) → `ReferenceResponse`.
  - `DELETE /api/projects/{pid}/references/{id}/pdf` → `ReferenceResponse`.
  - `GET /pdf/{filename}` — **public** (see security), streams the file from `coldp.pdf.dir` with
    `Content-Type: application/pdf` + `Content-Disposition: inline`. In dev/compose the backend serves
    this; in the GBIF deploy Apache's `/pdf` alias serves the dir directly (bypassing the backend) — same
    URL either way.
- **Security:** `SecurityConfig` permits `/pdf/**` (public read; the filename is unguessable). The
  upload/delete endpoints are under `/api/**` (authenticated + owner/editor). Vite dev proxy + the
  compose nginx proxy must forward `/pdf` to the backend (add to their proxy lists).
- **`ReferenceResponse`** exposes `pdfUrl` — `coldp.pdf.base-url + "/" + pdf` when `pdf != null`, else null
  (the frontend renders a view/remove control from it).
- **ColDP export** (`ReferenceColdpWriter`): the ColDP `link` column = **the absolute PDF URL when a PDF
  is attached AND the reference's own `link` is blank**; otherwise the reference's `link`. (So a hosted
  PDF becomes citable in the archive without overwriting a user-set publisher link.) Needs the absolute
  base — export uses `coldp.pdf.base-url` (configure it absolute in deploy).

### Frontend
- **`ReferenceForm`** (references editor): a **PDF** control — when no PDF, a `FileInput`/dropzone
  (`accept="application/pdf"`) → `attachPdf` (multipart via the `api()` `formData` branch already added
  for ColDP import) → shows the attached PDF (a "View PDF" link to `pdfUrl` + a "Remove" button →
  `removePdf`). Independent of the `link` text field (both shown).
- `api/references.ts`: `attachReferencePdf(pid, id, file)`, `removeReferencePdf(pid, id)`,
  `importRisReferences(pid, ris)`.

## Testing
- `RefMappingRisTest` (unit): representative RIS records (journal article, book, a Zotero/EndNote/Mendeley
  sample) → asserts the field mapping + type mapping + multi-record + unknown-tag tolerance.
- `ReferenceImportRisIT`: `POST import-ris` with a 2-record RIS → 2 references created with the right fields.
- `PdfServiceTest`/IT: store validates PDF magic + size cap (a non-PDF rejected, an oversize rejected);
  path-traversal on `resolve` blocked; `attachPdf` sets the column + `GET /pdf/{file}` streams it (public);
  `removePdf` clears + deletes; the ColDP export `link` = the PDF URL when link is blank, the user's link
  otherwise.
- Frontend: the RIS import control (paste → created); the PDF control (upload → view/remove) — MSW.

## Build phases (each shippable)
1. **RIS import** — `RefMapping.fromRis` + `importRis` + endpoint + the import-UI RIS option. (Self-contained.)
2. **PDF backend** — V20 + config + `PdfService` + attach/remove/serve endpoints + security + response
   `pdfUrl` + the export `link` logic.
3. **PDF frontend** — the `ReferenceForm` PDF control + api calls; Vite/nginx `/pdf` proxy wiring.

## Caveats / future
- One PDF per reference (a list is out of scope). Public serving (unguessable filename) — switch to an
  auth'd serve endpoint if private PDFs are ever needed.
- `coldp.pdf.base-url` defaults relative (`/pdf`); ColDP exports carry a relative link unless a deploy sets
  it absolute — document in the deploy env (already has `COLDP_PDF_DIR`; add `COLDP_PDF_BASE_URL`).
- No PDF text/metadata extraction (uploading a PDF doesn't populate the reference fields) — a possible
  future nicety.
- **Do not touch any `Jenkinsfile`** — the deploy CI is being authored separately.
