# ColDP editor — TODO / resume notes

Point me at this file next session. Fuller context lives in:
- Claude auto-memory (loaded automatically): `coldp-editor-design-decisions.md`, `features-backlog.md`
- Design spec: `docs/superpowers/specs/2026-07-07-coldp-editor-design.md`
- Plans: `docs/superpowers/plans/` · progress ledger: `.superpowers/sdd/progress.md`
- Business-rules / tooling backlog: `features.md`

## Where things stand (all on `main`, green)
Backend (Spring Boot 4.1 / Java 25 / Postgres 17) is a fairly complete phase-1 API: auth (ORCID + local), projects/metadata/members, references, name-usages (+ parsing, synonymy), classification tree (cycle-safe), soft locks + optimistic locking, audit change log, tasks/work-sessions, async validation engine + issue lifecycle, usage search (q + rank/status filters + total). `mvn verify` = 23 unit + 43 IT.
Frontend (React + Mantine): projects/metadata/members; **Tree** view (lazy tree + breadcrumb) and **Names** search table, both feeding a shared **TaxonDetail** (edit + synonyms + issues); create root/child/synonym; `⋮`/right-click action menu (add child/synonym, change status, delete). 45 tests + build.
Local run: `docker compose up -d` (Postgres on **5433**), backend `mvn spring-boot:run -Dspring-boot.run.profiles=dev` (seeds admin/admin), frontend `npm run dev` → :5173. See `README.md`.

## Next up (suggested order)
- [x] **Dev sample-data seeder** (`dev` profile) — DONE (commit a3a27ac). `DevSampleData` (@Order after DevBootstrap) seeds project "Felidae (sample data)": 10 accepted taxa (Animalia→…→Panthera leo/Felis catus), 2 synonyms, 1 reference. Atomic (one TransactionTemplate) + idempotent (skips on title). Needed an `AuditService` guard: `CurrentTask` is `@RequestScope`, so off-request writes (seeder/future jobs) now record an ungrouped change instead of `ScopeNotActiveException` — in-request behaviour unchanged. Verified end-to-end over HTTP + restart-skip.
- [x] **Tree move/reparent UI** — DONE (commit 432f30d). "Move…" in the ⋮/right-click menu (accepted only) → `MoveNameModal`: target-picker tree (reuses `ClassificationTree` with new `disabledId` prop → moved node + subtree greyed/non-expandable so no cycle can be picked) or "Make it a root". Re-reads version before PUT (409-safe); invalidates roots/children/detail/path. frontend 50 tests. **Live browser verification still pending** (fold into the shell-redesign session).
- [ ] **Link / unlink existing synonyms from the UI** (backend `PUT|DELETE /usages/{id}/synonym-of/{acceptedId}` exists; `SynonymList` is read-only today).

## Frontend remaining
- [ ] **References editor** (MRT table like Names + a reference form).
- [ ] **Issues dashboard** (list/filter + accept/reject/reopen; `POST /revalidate`) and a **changelog** view (`GET /changes`, group by task).
- [ ] Tree **virtualization** (lazy-per-node is fine for now; needed at Lepidoptera scale).

## Features backlog (`features.md`) — bigger pieces
- [ ] **Status business-rules + acc↔syn workflow**: only accepted names in tree/carry taxon info; synonyms → ≥1 accepted; no synonym chaining; misapplied = synonym; acc→syn demotion picks a new accepted + migrates taxon info (ask user).
- [ ] **More validation rules**: rank-vs-parent, genus-token-vs-parent, infraspecific-part-vs-parent, genus-year ≤ species-year, synonym→non-accepted, dangling pointers.
- [ ] **Supporting entities**: vernacular / distribution / properties / estimates / environments / geo-range (attach to accepted usages only).
- [ ] **Tools**: bulk name insert (TSV / texttree), homotypic grouping, reference import (DOI/Crossref, BibTeX, CSL-JSON) + DOI consolidation.
- [ ] **ColDP import/export** (phase 2/3) — where the deferred **Release** entity (version + issued + changelog) gets built.

## Known minor follow-ups (recorded in the ledger, non-blocking)
- [ ] Backend tests: add coverage for the `GET /issues?entityId=` filter and the etymology PUT round-trip (code correct on inspection, untested).
- [ ] `nomStatus` in TaxonDetail is a free-text input (shows the enum name); make it a Select later.
- [ ] Hide the "Sign in with ORCID" button when ORCID isn't configured (expose an `orcidEnabled` flag) — otherwise it dead-ends with an `invalid client_id` error locally.

## Working conventions (do not re-derive)
- Commit directly to `main` (no branches). Build with **JDK 25**: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/25.0.1-librca mvn ...` (default java 21 won't compile). No OrbStack Testcontainers workaround.
- Per-row actions = `⋮` + right-click menu (not text buttons). Create = toolbar ＋New + contextual add-child/synonym.
- Wire forms: name-usage `status` UPPERCASE, `rank` lowercase; project `nomCode` lowercase, `license` SPDX (`CC0-1.0`/`CC-BY-4.0`).
- IDE "cannot find module / undefined" diagnostics after subagent edits are usually **stale** — verify with an actual `mvn verify` / `npm run build`.
