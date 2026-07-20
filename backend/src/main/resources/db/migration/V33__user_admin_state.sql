-- Global admin flag + account lifecycle state on users (see docs .../2026-07-20-global-admin-*).
-- Existing rows default to ACTIVE (grandfathered) and non-admin. New ORCID self-signups are set to
-- PENDING by AppUserService.upsertFromOrcid; owner-added local accounts stay ACTIVE.
ALTER TABLE app_user ADD COLUMN admin BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE app_user ADD COLUMN state TEXT NOT NULL DEFAULT 'ACTIVE'; -- PENDING | ACTIVE | DISABLED
