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
- [x] **Tree move/reparent UI** — DONE (commit 432f30d). "Move…" in the ⋮/right-click menu (accepted only) → `MoveNameModal`: target-picker tree (reuses `ClassificationTree` with new `disabledId` prop → moved node + subtree greyed/non-expandable so no cycle can be picked) or "Make it a root". Re-reads version before PUT (409-safe); invalidates roots/children/detail/path. frontend 50 tests. **Live browser check DONE** in the shell-redesign session: moved Chordata→root then back under Animalia, tree refreshed live, disabled-self greying confirmed, DB persisted + restored.

## Shell redesign (header/footer/menu) — DONE
Spec `docs/superpowers/specs/2026-07-09-frontend-shell-redesign-design.md`, plan `…/plans/2026-07-09-frontend-shell-redesign.md`. Commits 83e917a…d8e1bcf (7 tasks). New shell: Mantine `AppShell` (header + **collapsible icon-rail sidebar** + footer), **slate** theme via `createTheme`, **light/dark toggle** (persisted; flash-prevention script in index.html). Sidebar = Projects + per-project Tree/Names/**Project**(=metadata page)/Members; `AppLayout` owns all chrome (derives projectId via `useMatch`); `ProjectLayout` slimmed to a guard; `ProjectSwitcher` now shows the current project + lands on Tree; footer shows `v{__APP_VERSION__}·{mode}`+GitHub. Fixes the old white-on-white header. 62 tests + build; browser-verified light+dark. Repo GitHub link is an assumed URL (no git remote configured).
## Synonym management & accepted↔synonym workflow (decomposed P1–P4)
Brainstormed into 4 sub-projects (each own spec+plan+ship). Sequencing: refactor first.
- [x] **P1 — Taxon-info refactor** — DONE (commit; spec `docs/superpowers/specs/2026-07-09-taxon-info-refactor-design.md`, plan `…/plans/2026-07-09-taxon-info-refactor.md`). Moved `extinct`/`environment`/`temporal` off `name_usage` → new `taxon_info` table (accepted-only, `(project_id,usage_id)` PK, CASCADE FK). `NameUsageMapper` LEFT JOINs it (wire shape unchanged, no frontend change); `NameUsageService.writeTaxonInfo` upserts when accepted-with-data else deletes. V9 migration. mvn verify 23u+45IT; dev-boot V9 verified (cols dropped, seed round-trips).
- [x] **P2 — acc↔syn workflow** — DONE (spec `…/specs/2026-07-09-p2-acc-syn-workflow-design.md`; commits 20fd8db backend + d4e9587 frontend). Atomic `POST /usages/{id}/demote` + `/promote` (advisory-locked, cycle-safe, 409). Demote: target picker (self+subtree greyed), Synonym/Misapplied, conditional children radio (new-accepted vs former-parent) + own-synonyms radio (re-point vs unassessed), sheds taxon_info. Promote: parent/root, drops links. Status-aware ⋮. **Browser-verified** (demote Chordata→syn of Animalia w/ child reparent + tree refresh; promote back via Names ⋮).
- [x] **P3 — Pro parte & interactive synonym links** — DONE (commit 723d990). Interactive `SynonymList` unlink (both directions, `unlinkSynonym`); synonym ⋮ **"Add accepted name…"** → `LinkAcceptedModal` (pro parte). Pro-parte **split on promote**: `PromoteRequest.keepAcceptedIds` → backend creates a copy synonym usage per kept relation; `PromoteModal` shows keep-checkboxes when pro parte. Browser-confirmed unlink control + Add-accepted item render.
- [x] **P4 — `genus_mismatch` validation rule** — DONE (commit 1de1da3). Accepted usage whose parsed genus ≠ nearest accepted genus-rank ancestor's name → WARNING. `NameUsageMapper.findAncestorGenusName` (recursive CTE); `RuleContext.ancestorGenusName` (4-arg convenience ctor keeps RuleTests intact). Surfaces in the existing IssueList; fires on reparents.

**Synonym management P1–P4 COMPLETE.** backend mvn verify 25u+50IT; frontend 75 tests; browser-verified. main clean.

## Validation + Issues dashboard + changelog — DONE (spec `…/specs/2026-07-09-validation-issues-changelog-design.md`)
- [x] **4 new validation rules** (commit): `rank_vs_parent` (WARN), `species_epithet_mismatch` (WARN), `genus_year_after_species` (INFO), `synonym_of_non_accepted` (ERROR). RuleContext + convenience ctors; NameUsageMapper lookups (findParentRank, 2 CTEs, non-accepted-target count).
- [x] **Issues dashboard** (commit) — `Issues` section: summary rollup + Revalidate + status/severity-filtered paged table + Accept/Reject/Reopen. Browser-verified (4 missing_published_in INFO on the seeded ref-less taxa).
- [x] **History (changelog) view** (commit) — `History` section: reverse-chron `GET /changes` with operation badge + entity + author + relative time + collapsible JSON diff + task filter. Browser-verified (demote/promote + seeder diffs render).

## References editor — DONE (spec `…/specs/2026-07-09-references-editor-design.md`)
- [x] **References editor** (commits): `References` section — citation search table + create/edit/delete `ReferenceForm`; **Import DOI** (backend `POST /references/resolve-doi` → Crossref via RestClient → CSL map → unsaved preview → pre-filled form; browser-verified live with 10.1038/nature12373) and **Import BibTeX** (backend `POST /references/import-bibtex` → jbibtex parse → bulk create). `RefMapping.fromCrossref/fromBibtex`; `CrossrefClient` (built with `RestClient.builder()` static — the `RestClient.Builder` bean isn't present). jbibtex dep. backend 35u+54IT; frontend 96 tests.

## Frontend remaining
- [ ] Tree **virtualization** (lazy-per-node is fine for now; needed at Lepidoptera scale).
- [ ] Issue **entity deep-link** (click an issue's entity → open it in the tree/detail — currently plain text).
- [ ] References list **total count** (endpoint returns a bare List → prev/next paging, no total/MRT; add a count for a richer table later).
- [ ] CSL-JSON import + DOI consolidation (find-DOI-for-existing) — same RefMapping.

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
