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
| `COLDP_EXPORT_DIR` | `${java.io.tmpdir}/coldp-exports` | ColDP export artifact storage dir |
| `COLDP_IMPORT_DIR` | `${java.io.tmpdir}/coldp-imports` | ColDP import upload storage dir |
| `COLDP_PDF_DIR` | `${java.io.tmpdir}/coldp-pdfs` | reference PDF storage dir |
| `COLDP_RELEASE_DIR` | `${java.io.tmpdir}/coldp-releases` | release snapshot storage dir |
| `COLDP_PDF_BASE_URL` | `/pdf` | public base URL for hosted reference PDFs (set to an absolute URL when PDFs are served from a different host/vhost than the API) |

In production, `COLDP_EXPORT_DIR` and `COLDP_RELEASE_DIR` should point at **persistent** storage, not `/tmp`: exports are swept on a TTL (`coldp.export.ttl`, default `P7D`) rather than immediately, and releases have **no retention sweep at all** — both need to survive host restarts and container recycles in between. `COLDP_IMPORT_DIR` and `COLDP_PDF_DIR` should be persistent too — uploaded ColDP archives and reference PDFs are served back to users after the request that wrote them.

Also relevant: `coldp.clb.base-url` (env `COLDP_CLB_BASE_URL`, default `https://api.checklistbank.org`) — the ChecklistBank API used for COL name matching and merge dataset lookups; point it at `https://api.dev.checklistbank.org` for a dev deployment.

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

**Docker (local):**

```bash
docker compose down -v      # drops the data volume
docker compose up -d        # fresh database
# restart the backend — Flyway re-migrates from scratch
```

**External PostgreSQL** (a deploy server, or any non-Docker instance) — drop and recreate
the database, then let the backend rebuild it on startup:

```bash
# stop the backend first so there are no active connections, then:
sudo -u postgres psql <<'SQL'
DROP DATABASE IF EXISTS coldp_editor;
CREATE DATABASE coldp_editor OWNER coldp_editor;
SQL
# start the backend — Flyway builds the fresh schema from V1__initial_schema.sql
```

> **After a migration squash you *must* recreate the database, not migrate in place.** When
> the incremental migrations are collapsed into a new `V1__initial_schema.sql` baseline, the
> `flyway_schema_history` recorded in an existing database no longer matches the migrations on
> disk (old versions are "applied but missing", `V1`'s checksum changed), so Flyway refuses to
> start. Dropping and recreating the database as above clears that history and applies the
> single baseline cleanly.

---

## Deployment

Two supported ways to run the whole stack — both documented in [`deploy/`](deploy/README.md):

- **Full Docker stack** (quick / demo) — Postgres + backend + frontend, all in containers:
  ```bash
  docker compose up --build
  # http://localhost:8088  ·  sign in with  admin / admin
  ```
  Runs the `dev` profile (seeds `admin`/`admin` + a sample project) — **local/demo only**. (This is different from the plain `docker compose up` above, which starts *only* Postgres.)

- **Server deployment** (production-style, e.g. GBIF dev) — three separate components: native **PostgreSQL 17**, the **backend jar** under systemd, and the **Vite bundle served by Apache2**, which reverse-proxies `/api` to the backend (same-origin, so ORCID works). Deployed login is **ORCID**; the `dev` profile is not used. Ready-to-adapt templates (DB init, env file, systemd unit, Apache vhost) + step-by-step instructions are in [`deploy/`](deploy/README.md). Domains: `blixa.dev.catalogueoflife.org` (dev) / `blixa.catalogueoflife.org` (prod).

The backend is a plain Spring Boot jar configured entirely through environment variables (see [Configuration](#configuration)), so any container or VM host works — the two setups above are just the supported defaults. `backend/Dockerfile` and `frontend/Dockerfile` build the two images.

### External PostgreSQL 17 (non-Docker)

For a server deployment the backend talks to a native PostgreSQL 17 instance. It creates and
migrates its own schema on boot (Flyway) — there is **no manual schema step** and no ORM
`ddl-auto`.

**1. Install PostgreSQL 17** (Debian/Ubuntu, via the PGDG apt repo — `pg_trgm` ships with the
standard server packages):

```bash
sudo apt install -y curl ca-certificates
sudo install -d /usr/share/postgresql-common/pgdg
sudo curl -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc \
  https://www.postgresql.org/media/keys/ACCC4CF8.asc
echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] \
https://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" \
  | sudo tee /etc/apt/sources.list.d/pgdg.list
sudo apt update && sudo apt install -y postgresql-17
```

**2. Create the role and database.** Make the app user the database **owner** — `pg_trgm` is a
*trusted* extension, so a database owner (no superuser needed) can let the migration create it,
along with every table and index:

```bash
sudo -u postgres psql <<'SQL'
CREATE ROLE coldp_editor LOGIN PASSWORD 'CHANGE_ME';
CREATE DATABASE coldp_editor OWNER coldp_editor;
SQL
```

To reach it from another host, also open `listen_addresses` in `postgresql.conf` and add a
`pg_hba.conf` line for the backend host (e.g. `host coldp_editor coldp_editor <backend-ip>/32 scram-sha-256`), then `sudo systemctl restart postgresql`. For a same-host backend the default
local socket / `127.0.0.1` is enough.

**3. Point the backend at it** — set the three `DB_*` variables (in the systemd unit's env file;
templates in [`deploy/`](deploy/README.md)):

```bash
DB_URL=jdbc:postgresql://localhost:5432/coldp_editor
DB_USER=coldp_editor
DB_PASSWORD=CHANGE_ME
```

On the next backend start, Flyway runs `V1__initial_schema.sql` against the empty database
(creating `pg_trgm` and all 32 tables). To reset later, see [Reset the database](#reset-the-database).

---

## Notes

- **JDK 25 is required to build.** If `mvn` picks up Java 21 you'll get compile errors — run `sdk env` in `backend/` or set `JAVA_HOME` explicitly.
- The **`dev` profile is for local development only** (it seeds `admin`/`admin`) — never enable it in production.
- Design docs and implementation plans live under [`docs/superpowers/`](docs/superpowers/); the roadmap/backlog is in [`features.md`](features.md).
