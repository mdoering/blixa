# Deploying the ColDP editor

Two supported ways to run the whole stack. For local development from source, see the repo root
[`README.md`](../README.md) instead (that runs Postgres in Docker and the backend/frontend from source).

---

## A. Full stack in Docker (quick / demo)

Everything in containers — Postgres 17 + backend + frontend — for a one-command run:

```bash
docker compose -f docker-compose.full.yml up --build      # from the repo root
# open http://localhost:8088  and sign in with  admin / admin
```

The backend runs under the `dev` profile here, so it seeds the `admin`/`admin` login and a small
sample project. **Demo/local only — never a real deployment.** The frontend container (nginx) serves
the SPA and proxies `/api`, `/login`, `/oauth2` to the backend, so it is same-origin on port 8088.
Data persists in the `coldp_editor_pg` volume; `docker compose -f docker-compose.full.yml down -v`
wipes it.

To enable ORCID in the compose, export `ORCID_CLIENT_ID` / `ORCID_CLIENT_SECRET` before `up`.

---

## B. GBIF dev — three components on a VM (production-style)

Postgres 17, JDK 25, and Apache2 are already present on the GBIF VM. The editor runs as **three
separate pieces**: native Postgres, the backend jar under systemd, and the Vite bundle served by
Apache (which reverse-proxies `/api` to the backend). Deployed login is **ORCID** — the `dev` profile
(admin/admin) is *not* used here. Real secrets live in the env file on the VM / the private `deploy`
repo, never in git.

The templates in this directory:

| File | Installs to | Purpose |
|---|---|---|
| `db-init.sql` | run once as postgres superuser | create the `coldp_editor` DB + role + `pg_trgm` |
| `coldp-editor.env.example` | `/etc/coldp-editor/coldp-editor.env` (fill secrets, `chmod 600`) | DB creds, ORCID, forward-headers, artifact dirs, CLB URL |
| `coldp-editor.service` | `/etc/systemd/system/coldp-editor.service` | run the backend jar (127.0.0.1:8080) |
| `apache-coldp-editor.conf` | `/etc/apache2/sites-available/` | serve the SPA + proxy `/api`,`/login`,`/oauth2` + TLS |

### 1. Database

```bash
sudo -u postgres psql -v pw="'a-real-password'" -f db-init.sql
```
Flyway creates the schema on first backend start — no manual migration step.

### 2. Backend jar

```bash
cd backend && sdk env && mvn -DskipTests clean package        # -> target/coldp-editor-backend-*.jar
sudo install -D -o col -g col target/coldp-editor-backend-*.jar /opt/coldp-editor/coldp-editor-backend.jar
sudo mkdir -p /opt/coldp-editor/data/exports /opt/coldp-editor/data/imports && sudo chown -R col:col /opt/coldp-editor

sudo install -D -m 600 -o col -g col deploy/coldp-editor.env.example /etc/coldp-editor/coldp-editor.env
sudo -e /etc/coldp-editor/coldp-editor.env                    # fill DB_PASSWORD + ORCID_* + review

sudo cp deploy/coldp-editor.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now coldp-editor
curl -fsS http://127.0.0.1:8080/api/ping && echo OK          # health check
```

### 3. Frontend (Apache)

```bash
cd frontend && npm ci && npm run build                        # -> dist/
sudo rsync -a --delete dist/ /var/www/coldp-editor/

sudo a2enmod proxy proxy_http ssl headers
sudo cp deploy/apache-coldp-editor.conf /etc/apache2/sites-available/coldp-editor.conf
# adjust ServerName + the TLS cert paths for dev vs prod
sudo a2ensite coldp-editor && sudo systemctl reload apache2
```

Register the ORCID app's redirect URI as
`https://editor.dev.catalogueoflife.org/login/oauth2/code/orcid` and set `ORCID_CLIENT_ID` /
`ORCID_CLIENT_SECRET` in the env file. Then browse to **https://editor.dev.catalogueoflife.org**.

### Redeploy

Rebuild the jar (step 2) → `sudo systemctl restart coldp-editor`; rebuild the SPA (step 3) → rsync to
`/var/www/coldp-editor`. Flyway applies any new migrations on backend restart.

> Domain is `editor.dev.catalogueoflife.org` (dev) / `editor.catalogueoflife.org` (prod) — change the
> `ServerName` + cert paths + the ORCID redirect URI together if the editor is rebranded.
