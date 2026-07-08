# ColDP Editor

A collaborative online editor for taxonomic checklists built around the
[ColDP](https://github.com/CatalogueOfLife/coldp) data format.

- **backend/** — Spring Boot 4.1 / Java 25 REST API (MyBatis, PostgreSQL 17, Flyway). ORCID + local login, projects, references, name usages, the classification tree, soft locks, an audit change log, work-sessions/tasks, and an async validation engine.
- **frontend/** — React 18 + TypeScript + Vite + Mantine SPA.

---

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| **JDK** | **25** (Liberica) | The default `java` (21) will **not** compile. `backend/.sdkmanrc` pins it — install via [sdkman](https://sdkman.io/): `sdk install java 25.0.1-librca`. |
| **Maven** | 3.9+ | Or use the system `mvn`. |
| **Node.js** | 20+ | with `npm`. |
| **Docker** | any recent | For the local Postgres below (and for the backend test suite, which uses Testcontainers). |

You can skip Docker if you already run a local PostgreSQL 17 — see [Configuration](#configuration).

---

## Run it locally

### 1. Start the database

From the repo root:

```bash
docker compose up -d
```

This starts PostgreSQL 17 with database `coldp_editor` (user/password `coldp_editor`) on host port **5433**. Port 5433 (not the standard 5432) avoids clashing with a PostgreSQL you may already run on 5432 — e.g. the main ChecklistBank dev database. The backend's `dev` profile is preconfigured for this port, and Flyway runs the migrations automatically on startup, so there is **no manual schema step**.

### 2. Run the backend

```bash
cd backend
sdk env                      # selects JDK 25 from .sdkmanrc
                             # (or: export JAVA_HOME=~/.sdkman/candidates/java/25.0.1-librca)
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

- The **`dev` profile** points the backend at the compose database on port 5433 (see `backend/src/main/resources/application-dev.yml`) and **seeds a local login** — username `admin`, password `admin` (see [DevBootstrap](backend/src/main/java/org/catalogueoflife/editor/DevBootstrap.java)). Without the `dev` profile a fresh database has **no way to log in** (there is no self-service signup, and ORCID needs real credentials).
- Flyway migrates the schema on first start.
- API is served at **http://localhost:8080**.

### 3. Run the frontend

In another terminal:

```bash
cd frontend
npm install
npm run dev
```

The Vite dev server runs at **http://localhost:5173** and proxies `/api`, `/oauth2`, and `/login` to the backend on `:8080` (see `frontend/vite.config.ts`).

### 4. Log in

Open **http://localhost:5173** and sign in with **`admin` / `admin`**. Create a project, then start editing references, name usages, and the classification tree.

> ORCID login is optional — set `ORCID_CLIENT_ID` / `ORCID_CLIENT_SECRET` (see below) to enable the "Sign in with ORCID" button.

---

## Configuration

The backend reads these environment variables (defaults shown):

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/coldp_editor` | JDBC URL |
| `DB_USER` | `coldp_editor` | database user |
| `DB_PASSWORD` | `coldp_editor` | database password |
| `ORCID_CLIENT_ID` | `unconfigured` | ORCID OAuth2 client id (optional) |
| `ORCID_CLIENT_SECRET` | `unconfigured` | ORCID OAuth2 client secret (optional) |

The base default `DB_URL` uses port 5432, but the **`dev` profile overrides it to 5433** (the compose port) via `application-dev.yml`. The `dev` seed user is also overridable: `editor.dev.username`, `editor.dev.password`, `editor.dev.display-name`.

To point at your own PostgreSQL instead of Docker, create the `coldp_editor` database and set the three `DB_*` variables accordingly (e.g. `DB_URL=jdbc:postgresql://localhost:5432/coldp_editor`), then run the backend (Flyway handles the schema). The `pg_trgm` extension is created automatically by a migration.

---

## Tests

```bash
# Backend (JDK 25; uses Testcontainers, so Docker must be running)
cd backend && JAVA_HOME=~/.sdkman/candidates/java/25.0.1-librca mvn clean verify

# Frontend
cd frontend && npm test
```

The backend test suite spins up its own throwaway PostgreSQL via Testcontainers — it does **not** use the `docker compose` database above.

---

## Reset the database

```bash
docker compose down -v      # drops the data volume
docker compose up -d        # fresh database
# restart the backend — Flyway re-migrates from scratch
```

---

## Notes

- **JDK 25 is required to build.** If `mvn` picks up Java 21 you'll get compile errors — run `sdk env` in `backend/` or set `JAVA_HOME` explicitly.
- The **`dev` profile is for local development only** (it seeds `admin`/`admin`) — never enable it in production.
- Design docs and implementation plans live under [`docs/superpowers/`](docs/superpowers/); the roadmap/backlog is in [`features.md`](features.md).
