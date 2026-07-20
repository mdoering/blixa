# Global admin & user lifecycle — design

**Date:** 2026-07-20
**Status:** approved

A global **admin** role (distinct from per-project owner/editor/viewer) and account **states**
(PENDING → ACTIVE → DISABLED), with admin screens to list users, approve pending signups, and
toggle the admin flag.

## Decisions
- **New ORCID self-signups start PENDING** (blocked until an admin approves). Owner-added local
  members and the dev admin start **ACTIVE** (someone already vouched for them). Existing users are
  grandfathered **ACTIVE**.
- **First admin(s)** are bootstrapped from a config allowlist `editor.admin.orcids` (comma-separated
  ORCIDs); those accounts auto-become **admin + ACTIVE** on login. The dev admin is admin + active.

## Backend
- **V33:** `app_user.is_admin BOOLEAN NOT NULL DEFAULT false`, `state TEXT NOT NULL DEFAULT 'ACTIVE'`.
  `UserState` enum = PENDING | ACTIVE | DISABLED. `AppUser` gains `isAdmin` + `state`; `AppUserMapper.insert`
  writes both.
- **AppUserService:**
  - `createLocal` → ACTIVE (owner-added / dev).
  - `upsertFromOrcid` → new user: ACTIVE + admin if the ORCID is in `editor.admin.orcids`, else PENDING;
    existing user: promote to admin+ACTIVE if now allowlisted, otherwise leave state untouched.
  - `loadUserByUsername` (local login): `enabled = (state == ACTIVE)`, so a disabled/pending local
    account can't log in.
- **Enforcement — `ActiveUserFilter`** (OncePerRequestFilter in the chain): an authenticated user whose
  state ≠ ACTIVE gets **403** on any `/api/**` except `/api/me`, `/api/auth/logout`, and the permitAll
  surface — so a PENDING user can load the SPA and see a "pending approval" screen but can't act.
- **Admin API** (`/api/admin/users`, global-admin only via `requireAdmin`):
  - `GET` → list users `{id, username, orcid, displayName, state, isAdmin}`.
  - `POST /{id}/state {state}` → approve (→ACTIVE) / disable (→DISABLED) / reactivate.
  - `POST /{id}/admin {admin}` → grant/revoke admin.
  - Guards: can't disable or demote **yourself** (self-lockout).
- **`/api/me`** returns `state` + `isAdmin`.

## Frontend
- `Me` gains `isAdmin` + `state`.
- **Pending gate:** when `state == 'PENDING'`, the app shell shows a "your account is awaiting admin
  approval" screen instead of the normal chrome.
- **Admin Users page** (`/admin/users`, admin-only nav): table of users with Approve / Disable /
  Reactivate and a make-admin toggle. `api/admin.ts` = `listUsers` / `setUserState` / `setUserAdmin`.

## Testing
- Backend `AdminUserIT`: bootstrap-admin via allowlist; a new ORCID-style user is PENDING and 403s on a
  protected endpoint; admin approves → 200; admin toggles/last-admin/self guards; non-admin can't reach
  `/api/admin/**`. Local `createLocal` users are ACTIVE.
- Frontend: pending gate render; admin page list + approve/toggle.

## Out of scope
Email to the user on approval/rejection (reuse the discussion mail infra later); fine-grained
per-endpoint admin permissions beyond the single global flag.
