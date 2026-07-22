# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**Blixa** is a collaborative online editor for taxonomic checklists built around the
[ColDP](https://github.com/CatalogueOfLife/coldp) format. Two parts:

- **`backend/`** — Spring Boot 4.1 / **Java 25** REST API (MyBatis, PostgreSQL 17, Flyway).
- **`frontend/`** — React 18 + TypeScript + Vite + Mantine SPA.

`README.md` has the full local-run, configuration, and deployment story. This file covers the
day-to-day dev loop and the architecture you'd otherwise have to reconstruct from many files.

## Commands

**Backend** (`cd backend`; requires **JDK 25** — `mvn` on Java 21 fails to compile, so run
`sdk env` first, which reads `.sdkmanrc`):

- Compile: `mvn -o compile` / `mvn -o test-compile`
- Unit tests (surefire): `mvn -o test -Dtest=<FQN>` — the **fully-qualified** class name is
  required (`-Dtest=SimpleName` finds nothing).
- Integration tests (`*IT`, failsafe, **Testcontainers → Docker must be running**):
  `mvn -o test-compile failsafe:integration-test failsafe:verify -Dit.test=<FQN>`.
  Full suite: `mvn verify`. ITs spin up their own throwaway Postgres — they do **not** use the
  compose DB.
- The Docker daemon here is **OrbStack**; start it headlessly with **`orb start`** (a
  "start VM: timed out" line is harmless — `docker info` confirms the engine is up). Colima is
  installed as a fallback.

**Frontend** (`cd frontend`):

- `npm run dev` — Vite dev server on :5173, proxies `/api` `/oauth2` `/login` `/pdf` to :8080.
- `npm run build` — `tsc -b && vite build` (this is also the type-check gate; there is **no
  ESLint** in the repo).
- `npm test` — `vitest run`. Single file: `npx vitest run src/<path>/File.test.tsx`.

**Run the app locally:** `docker compose up db` (Postgres on host **:5433**) → backend
`mvn spring-boot:run -Dspring-boot.run.profiles=dev` (the `dev` profile points at :5433 and seeds
a local **admin/admin** login) → `npm run dev`. Full details + ORCID setup in `README.md`.

## Architecture (big picture)

**Backend** lives under `org.catalogueoflife.editor`, packaged **by domain** (`name` — which also
holds references, `tree`, `project`, `validation`, `discussion`, `lock`, `auth`, `admin`, `child`,
`homotypy`, `merge`/`mergerecords`, `coldp/{export,imprt,io}`, `release`).

- **Persistence is MyBatis, not JPA.** Mappers are `*Mapper` interfaces with `@Select`/`@Insert`
  annotations (a few `.xml` mappers). The schema is owned entirely by **Flyway** — migrations in
  `backend/src/main/resources/db/migration` as `V<n>__*.sql`, no `ddl-auto`. A schema change =
  a new migration file with the next version number.
- **Domain core:** a `project` scopes all data. `name_usage` is the taxon/name row; the
  **classification tree** is parent links on `name_usage` (the accepted backbone) plus
  `synonym_accepted` linking synonyms → their accepted name. `status` ∈
  ACCEPTED / SYNONYM / MISAPPLIED / UNASSESSED (UNASSESSED = provisional; a taxon that can still
  hold supplementary data). `reference` is CSL-shaped (the year lives in `issued`). Around these:
  soft **locks**, an audit **changelog**, work-sessions/**tasks**, async **validation** issues,
  ORCID-keyed **discussions**, ColDP **export**/**import** + **release** snapshots, and a
  supervised **merge**.
- **Reuse the CLB reader library — do not hand-roll ColDP or TextTree.** Depend on
  `life.catalogue.*` and `org.gbif.txtree`: `ColdpTerm` + `TabWriter` for TSV (see
  `coldp/io/ColdpTsv.java`), `ColdpReader` for reading, `org.gbif.txtree.Tree` / `SimpleTreeNode`
  for TextTree (read on import under `coldp/imprt`, and `SimpleTreeNode.print(...)` to write).
  Name/rank parsing comes from **name-parser 5.x** (ships Rust FFM native jars).
- **Read model / search:** `GET /usages?q&rank&status&limit&offset` → `UsagePage{items,total}`
  (server-side pagination, `pg_trgm` fuzzy `q`); `GET /references?q&yearFrom&yearTo`. External
  calls go through SSRF-guarded `RestClient` `@Component`s: **ChecklistBank** (COL matching, merge,
  compare — `coldp.clb.base-url`) and **Crossref/DataCite** (`CrossrefClient`/`DataciteClient`,
  DOI → structured reference).
- **Auth:** ORCID OAuth2 + a local `dev`-profile login. Per-project roles owner/editor/viewer
  (enforced via `projects.requireRole`), a global admin flag, and `ActiveUserFilter` gating
  non-ACTIVE users.

**Frontend** (`src/`): routes in `App.tsx`; feature folders (`tree`, `names`, `references`,
`discussions`, `projects`, `merge`, `child`, …). API wrappers in `src/api/*` sit over a shared
`api()` client; **TanStack Query** holds server state and **mantine-react-table** drives the
search tables. Tests are **vitest + @testing-library + MSW** (handlers in `src/test/server.ts`,
polyfills in `src/test/setup.ts`). Binary downloads use a plain `<a href download>` (see
`api/export.ts`), not the JSON `api()` client.

## Conventions & gotchas

- **Work on `main`, no feature branches.** Pushing to `main` triggers the Blixa **Jenkins** dev
  deploy (job `col-blixa`) → `blixa.dev.catalogueoflife.org`.
- **Design-first:** one markdown per feature under
  `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`. The live roadmap is **`backlog.md`**
  (it supersedes the older `features.md`).
- Keep per-project numeric **name ids stable** across edits — it matters for the future
  supervised merge.
- Secrets live only in the private `deploy/` repo; the backend is configured entirely via env
  vars (see `README.md` → Configuration).
