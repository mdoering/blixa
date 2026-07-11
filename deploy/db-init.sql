-- One-time provisioning for the ColDP editor on a native PostgreSQL 17 (the GBIF dev setup).
-- The backend's Flyway migrations create the SCHEMA on startup; this script only creates the
-- database + login role and enables pg_trgm (which the migration expects to already exist when the
-- app role is not a superuser). Run once as a Postgres superuser:
--
--   sudo -u postgres psql -v pw="'a-real-password'" -f db-init.sql
--
-- Do NOT commit a real password — pass it via -v pw (the real value lives in the env file / the
-- private deploy repo, never here).
\set pw '''CHANGE_ME'''

CREATE ROLE coldp_editor WITH LOGIN PASSWORD :pw;
CREATE DATABASE coldp_editor OWNER coldp_editor;

\connect coldp_editor
-- pg_trgm powers the name/citation fuzzy search + the merge matcher's POSSIBLE_FUZZY fallback.
-- Created here as superuser so the migration's `CREATE EXTENSION IF NOT EXISTS pg_trgm` is a no-op
-- even though the coldp_editor role can't create extensions.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
