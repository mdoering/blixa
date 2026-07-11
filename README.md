# Blixa

A collaborative online editor for taxonomic checklists built around the
[ColDP](https://github.com/CatalogueOfLife/coldp) data format.

- **backend/** — Spring Boot 4.1 / Java 25 REST API (MyBatis, PostgreSQL 17, Flyway). ORCID + local login, projects, references, name usages, the classification tree, soft locks, an audit change log, work-sessions/tasks, and an async validation engine.
- **frontend/** — React 18 + TypeScript + Vite + Mantine SPA.

---

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| **JDK** | **25** (Liberica) | The default `java` (21) will **not** compile. `backend/.sdkmanrc` pins the exact build — install it via [sdkman](https://sdkman.io/): `cd backend && sdk env install` (reads `.sdkmanrc`), then `sdk env` selects it. Any JDK 25 works; Liberica is what `.sdkmanrc` pins. |
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

This starts PostgreSQL 17 with database `coldp_editor` (user/password `coldp_editor`) on host port **5433**. Port 5433 (not the standard 5432) avoids clashing with any PostgreSQL you may already run on 5432. The backend's `dev` profile is preconfigured for this port, and Flyway runs the migrations automatically on startup, so there is **no manual schema step**.

### 2. Run the backend

```bash
cd backend
sdk env                      # selects JDK 25 from .sdkmanrc
                             # (no sdkman? point JAVA_HOME at any JDK 25 install)
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

Open **http://localhost:5173** and sign in with **`admin` / `admin`** (the local-login form on the sign-in page). Create a project, then start editing references, name usages, and the classification tree.

> **Use local login for local dev.** The "Sign in with ORCID" button will fail with *"Invalid parameter: client_id"* unless ORCID is configured — see [ORCID login](#orcid-login-optional) below. For local development, `admin` / `admin` is all you need.

### ORCID login (optional)

ORCID is primarily for the deployed environments (frontend + backend served same-origin, with real credentials). To enable it locally:

1. Register an ORCID API client — at [orcid.org](https://orcid.org) (Developer tools) or the [ORCID Sandbox](https://sandbox.orcid.org) — to obtain a **Client ID** and **Client Secret**.
2. In that ORCID app registration, add the redirect URI **`http://localhost:8080/login/oauth2/code/orcid`**.
3. Run the backend with `ORCID_CLIENT_ID` and `ORCID_CLIENT_SECRET` set (e.g. `ORCID_CLIENT_ID=APP-… ORCID_CLIENT_SECRET=… mvn spring-boot:run -Dspring-boot.run.profiles=dev`).

Note: in the split dev setup the ORCID callback goes directly to the backend on `:8080` (not through the Vite proxy on `:5173`), so the post-login landing is rougher than in a deployed same-origin build — another reason `admin`/`admin` is the smoother local path. The app is preconfigured for **production** ORCID endpoints (`orcid.org`); using the Sandbox additionally requires overriding the provider URIs in `application.yml`.

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
cd backend && sdk env && mvn clean verify   # sdk env selects JDK 25 from .sdkmanrc

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

## Deployment

Two supported ways to run the whole stack — both documented in [`deploy/`](deploy/README.md):

- **Full Docker stack** (quick / demo) — Postgres + backend + frontend, all in containers:
  ```bash
  docker compose -f docker-compose.full.yml up --build
  # http://localhost:8088  ·  sign in with  admin / admin
  ```
  Runs the `dev` profile (seeds `admin`/`admin` + a sample project) — **local/demo only**. (This is different from the plain `docker compose up` above, which starts *only* Postgres.)

- **Server deployment** (production-style, e.g. GBIF dev) — three separate components: native **PostgreSQL 17**, the **backend jar** under systemd, and the **Vite bundle served by Apache2**, which reverse-proxies `/api` to the backend (same-origin, so ORCID works). Deployed login is **ORCID**; the `dev` profile is not used. Ready-to-adapt templates (DB init, env file, systemd unit, Apache vhost) + step-by-step instructions are in [`deploy/`](deploy/README.md). Domains: `editor.dev.catalogueoflife.org` (dev) / `editor.catalogueoflife.org` (prod).

The backend is a plain Spring Boot jar configured entirely through environment variables (see [Configuration](#configuration)), so any container or VM host works — the two setups above are just the supported defaults. `backend/Dockerfile` and `frontend/Dockerfile` build the two images.

---

## Notes

- **JDK 25 is required to build.** If `mvn` picks up Java 21 you'll get compile errors — run `sdk env` in `backend/` or set `JAVA_HOME` explicitly.
- The **`dev` profile is for local development only** (it seeds `admin`/`admin`) — never enable it in production.
- Design docs and implementation plans live under [`docs/superpowers/`](docs/superpowers/); the roadmap/backlog is in [`features.md`](features.md).
