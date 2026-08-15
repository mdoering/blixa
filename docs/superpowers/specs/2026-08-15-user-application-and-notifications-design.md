# User application (required email + optional message) + lifecycle emails

**Date:** 2026-08-15
**Status:** Design — awaiting spec review

## Problem

New users self-register via ORCID and land in **PENDING**, waiting for an admin to
approve them. Two gaps:

1. **We never capture an email.** `AppUser.email` exists in the schema but
   `setEmail(...)` is never called anywhere — ORCID login doesn't return an email and
   nothing else sets one. So we cannot contact users at all (the existing
   `DiscussionNotifier` silently skips every follower for the same reason).
2. **No lifecycle notifications.** An approved user is never told they can now log in,
   and admins are never told a new application is waiting.

## Goal

- **Require an email to apply.** A self-registered ORCID user must supply a (required)
  email and an (optional) short message before their application is complete.
- **Notify admins** when a new application is submitted.
- **Notify the applicant** when an admin approves them.
- Let users **edit their email** later from account settings.

Decided during brainstorming: reuse the existing PENDING/ACTIVE/DISABLED states (no new
state); the application message is **optional**; admins are emailed on new applications;
`EmailService` is lifted into a neutral package shared by both notifiers.

## Non-goals

- **Email verification / confirmation links.** For an admin-approved internal editor,
  we trust the entered address. Out of scope.
- **Prefilling email from ORCID.** ORCID emails are usually private; we ask the user
  directly instead.
- **Public local self-registration.** Real registration is ORCID self-signup; local
  accounts are created ACTIVE by an owner or the dev bootstrap (unchanged).

## Data model

`app_user.email` already exists (nullable). One new Flyway migration:

```
-- V10__application_note.sql
ALTER TABLE app_user ADD COLUMN application_note text;
```

`AppUser` gains an `applicationNote` field (getter/setter). `SELECT *` maps it
automatically (`map-underscore-to-camel-case: true`); the `@Insert`/`@Update` SQL in
`AppUserMapper` must add `application_note` to their column lists explicitly.

## Backend

### Endpoints (`MeController`)
- `GET /api/me` — add `email` to the payload (empty string when null) so the SPA can tell
  whether the application is complete and can prefill account settings.
- `PUT /api/me/email` `{email}` — edit email (used by account settings). ACTIVE users.
- `PUT /api/me/application` `{email, note}` — the applicant gate. `email` **required**
  (400 if blank/malformed); `note` optional (trimmed; stored null when blank).

### `ActiveUserFilter` (important)
The gate's `ALLOW` set is an **exact-match** set, so a PENDING user is 403'd on anything
but `/api/me`, logout, ping, config. **Add `/api/me/application`** to `ALLOW` so a pending
applicant can actually submit. (`/api/me/email` stays gated — pending users edit their
email through the application form, not account settings.)

### `AppUserService`
- `updateEmail(userId, rawEmail)` — validate non-blank + a permissive format check
  (`^[^@\s]+@[^@\s]+\.[^@\s]+$`), set, `update`, return the user.
- `submitApplication(userId, rawEmail, rawNote)` — validate email (required); note
  optional. Compute `wasIncomplete = old email is blank`. Persist email + note. If
  `wasIncomplete`, call `userNotifier.notifyAdminsOfApplication(user)` (best-effort, after
  the write) — so admins are notified once on first completion, not on later edits.

### `AdminUserService.setState`
Capture the old state before updating. After a successful `update`, if
`oldState == PENDING && newState == ACTIVE`, call `userNotifier.notifyApproved(target)`.
(Deliberately *not* on `DISABLED → ACTIVE` reactivation — the "approved" copy would be
wrong there, and a reactivated user wasn't newly approved.)

### `UserNotifier` (new) — mirrors `DiscussionNotifier`
- `notifyApproved(user)` — if the user has an email, send "Your Blixa account has been
  approved" with a login link (`coldp.mail.base-url` + `/signin`).
- `notifyAdminsOfApplication(applicant)` — email every **ACTIVE admin that has an email**
  with the applicant's display name, ORCID, email, and message, plus a link to
  `/admin/users`. Needs an `AppUserMapper` query for active admins with a non-null email
  (or filter `findAll()` in-memory — small table). Best-effort: never throws.

### `EmailService` refactor
Move `discussion/EmailService.java` → a neutral `org.catalogueoflife.editor.notify`
package (both `UserNotifier` and `DiscussionNotifier` use it). Update `DiscussionNotifier`
+ any test import. Behaviour unchanged (still gated on `spring.mail.host` + `coldp.mail.from`,
still logs-and-skips otherwise).

### `AdminUserResponse`
Add `email` and `applicationNote` so the admin page has context.

## Frontend

### Types & API
- `Me` gains `email: string`.
- `api/auth.ts`: `updateEmail(email)` → `PUT /api/me/email`; `submitApplication(email,
  note)` → `PUT /api/me/application`. Both return the updated `Me`.
- `api/admin.ts` `AdminUser` type + list: add `email` and `applicationNote`.

### `AccountModal`
Add an **Email** `TextInput` (prefilled from `me.email`, validated non-blank + format).
Save persists username (existing endpoint) and email (`updateEmail`). Email is shown as a
normal editable field; ORCID stays read-only.

### `PendingApprovalPage` → application-aware
- `DISABLED` → unchanged.
- `PENDING` **and** `me.email` is blank → an **"Apply for access"** form: required Email
  + optional Message (`Textarea`) + **Submit** → `submitApplication` → invalidate `['me']`.
  On success the same page re-renders as the waiting screen.
- `PENDING` **and** `me.email` present → the current "awaiting approval" screen, plus a
  small **"Edit application"** link that reopens the prefilled form (also
  `submitApplication`; allowed because the endpoint is in `ALLOW`). "Sign out" stays.

### `AdminUsersPage`
Add **Email** and **Message** columns (message can wrap/truncate) so an admin can judge a
PENDING application before approving.

## Notification copy (plain text)

- **To applicant on approval** — subject `Your Blixa account has been approved`; body: a
  line of welcome + `${base}/signin`.
- **To admins on new application** — subject `New Blixa access request from ${name}`;
  body: name, ORCID, email, the message (if any), and `${base}/admin/users`.

No new config: reuses `coldp.mail.from` and `coldp.mail.base-url`. When mail isn't
configured, everything still works — emails are logged and skipped.

## Data flow

```
ORCID first login ─► upsertFromOrcid ─► PENDING (no email)
   SPA: RequireAuth ─► PendingApprovalPage
        email blank ─► "Apply for access" form
          PUT /api/me/application {email, note}
            AppUserService.submitApplication ─(first time)─► UserNotifier.notifyAdminsOfApplication
        email present ─► "awaiting approval"

Admin approves (POST /api/admin/users/{id}/state {state:"ACTIVE"})
   AdminUserService.setState ─ PENDING→ACTIVE ─► UserNotifier.notifyApproved(applicant)
```

## Testing

**Backend**
- `AppUserService.submitApplication`: rejects blank/malformed email (400); stores email +
  trimmed note (null when blank); reports `wasIncomplete` only when email was blank.
- `AppUserService.updateEmail`: validation + persistence.
- `AdminUserService.setState`: PENDING→ACTIVE calls `notifyApproved`; ACTIVE→DISABLED and
  DISABLED→ACTIVE reactivation do **not** notify. Use a mock `UserNotifier`.
- `UserNotifier`: `notifyApproved` sends only when email present; `notifyAdminsOfApplication`
  targets only ACTIVE admins with an email. Mock `EmailService`.
- `MeController` (MockMvc): `PUT /api/me/application` happy path + validation; a PENDING
  user is **not** 403'd on it (ActiveUserFilter allow-list).
- `DiscussionNotifier` test import updated for the moved `EmailService`.

**Frontend** (vitest + MSW)
- `AccountModal`: email prefilled from `me`, saves via `PUT /api/me/email`.
- `PendingApprovalPage`: DISABLED screen; PENDING+no-email shows the apply form and
  submits (email required, message optional); PENDING+email shows waiting + edit.
- `AdminUsersPage`: renders email + message columns.
- `src/test/server.ts`: default `me` handler gains `email`.

## Files touched

- **New:** `V10__application_note.sql`, `notify/UserNotifier.java`,
  `frontend/.../PendingApprovalPage` apply-form (same file), tests.
- **Moved:** `discussion/EmailService.java` → `notify/EmailService.java`.
- **Edited:** `AppUser`, `AppUserMapper`, `AppUserService`, `MeController`,
  `ActiveUserFilter`, `AdminUserService`, `AdminUserController`/`AdminUserResponse`,
  `DiscussionNotifier` (import); frontend `types.ts`, `api/auth.ts`, `api/admin.ts`,
  `AccountModal.tsx`, `PendingApprovalPage.tsx`, `AdminUsersPage.tsx`, `test/server.ts`.
