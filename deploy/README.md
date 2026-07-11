# Deploying Blixa

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
Data persists in the `blixa_pg` volume; `docker compose -f docker-compose.full.yml down -v`
wipes it.

To enable ORCID in the compose, export `ORCID_CLIENT_ID` / `ORCID_CLIENT_SECRET` before `up`.

---

## B. GBIF dev — three components on a VM (production-style)

Postgres 17, JDK 25, and Apache2 are already present on the GBIF VM. Blixa runs as **three
separate pieces**: native Postgres, the backend jar under systemd, and the Vite bundle served by
Apache (which reverse-proxies `/api` to the backend). Deployed login is **ORCID** — the `dev` profile
(admin/admin) is *not* used here. Real secrets live in the env file on the VM / the private `deploy`
repo, never in git.

The templates in this directory:

| File | Installs to | Purpose |
|---|---|---|
| `db-init.sql` | run once as postgres superuser | create the `blixa` DB + role + `pg_trgm` |
| `blixa.env.example` | `/home/col/bin/blixa/blixa.env` (fill secrets, `chmod 600`) | DB creds, ORCID, forward-headers, artifact dirs, CLB URL |
| `col-blixa.service` | `/etc/systemd/system/col-blixa.service` | run the backend jar (127.0.0.1:8111) |
| `apache-blixa.conf` | `/etc/apache2/sites-available/` | serve the SPA + proxy `/api`,`/login`,`/oauth2` + TLS |

### 1. Database

```bash
sudo -u postgres psql -v pw="'a-real-password'" -f db-init.sql
```
Flyway creates the schema on first backend start — no manual migration step.

### 2. Backend jar

```bash
cd backend && sdk env && mvn -DskipTests clean package        # -> target/blixa-backend-*.jar
sudo install -D -o col -g col target/blixa-backend-*.jar /home/col/bin/blixa/blixa-backend.jar
sudo mkdir -p /mnt/auto/col/blixa/exports /mnt/auto/col/blixa/imports /mnt/auto/col/blixa/pdfs && sudo chown -R col:col /mnt/auto/col/blixa

sudo install -D -m 600 -o col -g col deploy/blixa.env.example /home/col/bin/blixa/blixa.env
sudo -e /home/col/bin/blixa/blixa.env                          # fill DB_PASSWORD + ORCID_* + review

sudo cp deploy/col-blixa.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now col-blixa
curl -fsS http://127.0.0.1:8111/api/ping && echo OK          # health check
```

### 3. Frontend (Apache)

```bash
cd frontend && npm ci && npm run build                        # -> dist/
sudo rsync -a --delete dist/ /var/www/html/blixa/

sudo a2enmod proxy proxy_http ssl headers
sudo cp deploy/apache-blixa.conf /etc/apache2/sites-available/blixa.conf
# adjust ServerName for dev vs prod
sudo a2ensite blixa && sudo systemctl reload apache2
```

Register the ORCID app's redirect URI as
`https://blixa.dev.catalogueoflife.org/login/oauth2/code/orcid` and set `ORCID_CLIENT_ID` /
`ORCID_CLIENT_SECRET` in the env file. Then browse to **https://blixa.dev.catalogueoflife.org**.

### Redeploy

Rebuild the jar (step 2) → `sudo systemctl restart col-blixa`; rebuild the SPA (step 3) → rsync to
`/var/www/html/blixa`. Flyway applies any new migrations on backend restart.

> Domain is `blixa.dev.catalogueoflife.org` (dev) / `blixa.catalogueoflife.org` (prod) — change the
> `ServerName` + the ORCID redirect URI together if the deployment is rebranded.
