# Project invitations by email

**Date:** 2026-09-19
**Status:** Design — awaiting spec review

## Problem

A project owner can only add members who already exist in Blixa (the username form on the
Members page). Someone who has never signed in can't be added: in ORCID mode `setMember` 404s
on an unknown username, and a fresh ORCID sign-up lands in **PENDING** until an admin approves
it. There is no way for an owner to bring a collaborator in directly.

## Goal

- A project **owner** invites a person **by email** with a role and an optional personal message.
- Blixa sends a standard invitation email with the project name and an accept link; the inviting
  owner is **CC'd** (and set as Reply-To).
- The invitee opens the link, signs in with ORCID (creating their account if needed), clicks
  **Accept**, and lands in the project as a member.

Decided during brainstorming:

- **Accepting auto-activates.** The owner's invitation vouches for the invitee: a PENDING account
  becomes ACTIVE on accept, no admin step, no admin notification.
- **Browser-carried token (approach A).** The invite token survives the ORCID round-trip via
  `localStorage`, not an HTTP-session attribute or a custom OAuth2 request resolver.
- **The link is the credential.** Whoever holds the link can accept. We deliberately do **not**
  match invitations to accounts by email — `app_user.email` is self-entered and not unique, so
  email matching would let someone claim another person's invitation.

## Non-goals

- Auto-adding an *existing* user by email lookup (see above; the username form covers them).
- Email verification beyond "they received the link" (accepting fills a blank account email from
  the invitation, which is the closest we get).
- Bulk invitations / CSV upload.
- An admin-side view of invitations.

## Data model

```sql
-- V11__project_invitation.sql
CREATE TABLE project_invitation (
  id          integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id  bigint NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  email       text NOT NULL,
  role        text NOT NULL CHECK (role IN ('owner','editor','viewer')),
  message     text,
  token       text NOT NULL UNIQUE,
  invited_by  bigint REFERENCES app_user(id) ON DELETE SET NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  expires_at  timestamptz NOT NULL,
  accepted_at timestamptz,
  accepted_by bigint REFERENCES app_user(id) ON DELETE SET NULL
);
CREATE INDEX project_invitation_project_idx ON project_invitation (project_id);
```

- `token`: 32 bytes from `SecureRandom`, base64url without padding (43 chars). Stored in plain
  text so the owner can **Copy link** later; it's a 256-bit unguessable capability.
- `expires_at` = now + **30 days** on create and on resend.
- An invitation is *pending* while `accepted_at IS NULL`; *expired* when additionally
  `expires_at < now()`. Revoke = hard `DELETE`.

## Backend

New package `org.catalogueoflife.editor.invite`, shaped like `join/`:
`ProjectInvitation` (model), `InvitationMapper` (MyBatis annotations), `InvitationService`,
`InvitationController`, `InvitationNotifier`, and `dto/` records.

### Owner endpoints — `/api/projects/{pid}/invitations`

All gate through `ProjectService.requireOwner` (non-owner member → 403, non-member → 404).

| Method | Path | Body | Result |
|---|---|---|---|
| `POST` | `/api/projects/{pid}/invitations` | `{email, role, message?}` | 201 `InvitationResponse` |
| `GET` | `/api/projects/{pid}/invitations` | — | `InvitationResponse[]` — pending (not accepted), newest first, expired included |
| `POST` | `/api/projects/{pid}/invitations/{id}/resend` | — | `InvitationResponse` |
| `DELETE` | `/api/projects/{pid}/invitations/{id}` | — | 204 |

`InvitationResponse` = `{id, email, role, message, invitedBy (display name), createdAt,
expiresAt, expired, acceptUrl}` where `acceptUrl = coldp.mail.base-url + "/invite/" + token`.

**Create** (`InvitationService.create`):
1. `requireOwner`.
2. Validate email with the same permissive pattern as `AppUserService.EMAIL` (400 otherwise);
   trim; `role` via `Role.fromDb` (400 on junk); message trimmed, blank → null.
3. If a *pending, unexpired* invitation for the same project + email (case-insensitive) exists
   → **409** "already invited — use Resend". An expired one doesn't block; it stays listed until
   revoked or resent.
4. Insert, then `InvitationNotifier.sendInvitation(invitation, project, inviter)` (best-effort).

**Resend**: `requireOwner`, 404 if the id isn't a pending invitation of this project; new token,
`expires_at = now + 30d`, update, send the email again. The old link stops working.

**Revoke**: `requireOwner`, delete where `project_id` and `id` match and `accepted_at IS NULL`;
0 rows → 404.

### Invitee endpoints

**`GET /api/public/invitations/{token}`** — unauthenticated (already covered by the
`/api/public/**` permitAll + CSRF exemption). Returns
`{projectTitle, invitedBy, role, message, status}` with `status ∈ VALID | EXPIRED | ACCEPTED`;
unknown token → 404. Lives on the invite controller (or a small `PublicInvitationController`)
under the public prefix.

**`POST /api/invitations/{token}/accept`** — authenticated. One `@Transactional` method:
1. Look up by token; unknown → 404; `accepted_at` set or `expires_at` past → **410 Gone**.
2. Load the current user. `DISABLED` → **403** (an invitation must not undo an admin's disable).
3. `PENDING` → set `ACTIVE`. If the user's `email` is blank → set it to the invitation email.
   Persist via `AppUserMapper.update` only if something changed.
4. If the user is not yet a member of the project → `ProjectMemberMapper.upsert` with the
   invited role. If they already are, **leave their role untouched** (never downgrade an owner).
5. Set `accepted_at = now()`, `accepted_by = userId`.
6. Return `{projectId}`.

### `ActiveUserFilter`

Add a prefix exemption: `path.startsWith("/api/invitations/")`, alongside the exact-match
`ALLOW` set, so a PENDING user can call accept. Only the accept endpoint lives under that prefix
(the owner-side endpoints are under `/api/projects/...` and stay gated). Update the class comment.

### `EmailService`

Add `send(String to, String cc, String replyTo, String subject, String text)`; blank `cc` /
`replyTo` are simply not set. The existing `send(to, subject, text)` delegates with nulls.
Behaviour otherwise unchanged (still logs-and-skips when mail isn't configured, never throws).

### `InvitationNotifier`

`sendInvitation(ProjectInvitation inv, Project project, AppUser inviter)` — best-effort, never
throws. Inviter name = display name, falling back to username. CC and Reply-To = the inviter's
email when present, otherwise omitted.

## Email copy (plain text)

```
Subject: You're invited to join "<Project title>" on Blixa

<Owner name> has invited you to join the project "<Project title>" on Blixa, the
collaborative editor for taxonomic checklists, as <an editor|a viewer|an owner>.

<Owner name> wrote:
<personal message>                      ← whole block only when a message was given

To accept, open the link below and sign in with your ORCID iD. If you don't have one
yet, you can register for free at orcid.org during sign-in.

<base-url>/invite/<token>

This invitation expires on <d MMMM yyyy>. If you weren't expecting it, you can ignore
this email.
```

No new configuration: reuses `coldp.mail.from` and `coldp.mail.base-url`.

## Frontend

### API & types
- `src/api/invitations.ts`: `listInvitations(pid)`, `createInvitation(pid, {email, role,
  message})`, `resendInvitation(pid, id)`, `revokeInvitation(pid, id)`,
  `getInvitationPreview(token)` (public), `acceptInvitation(token)`.
- `Invitation` and `InvitationPreview` types in `src/api/types.ts`.

### Members page (owners only)
- **"Invite by email"** button beside the existing username form → modal with Email (required,
  format-checked), Role (`Select`, default `editor`), Message (optional `Textarea`). Submit →
  `createInvitation`, invalidate `['invitations', pid]`, success toast; 409 shows its message.
- **"Pending invitations"** section under the members table, same `Paper` layout as the join
  requests: email, role, "invited by X · 3 days ago", an **expired** badge when due, and actions
  **Copy link** (clipboard + toast), **Resend**, **Revoke** (confirm modal).

### Accept page — `/invite/:token` (new, public route outside `RequireAuth`)
`src/invite/InviteAcceptPage.tsx`, rendered in the same centred-card style as `LoginPage`.
- Fetch the preview. `EXPIRED` / `ACCEPTED` / 404 → explain, clear any stored token, no button.
- `VALID`, **not signed in** (`useMe` errors): store `token` in `localStorage` under
  `blixa.pendingInvite` (every access wrapped in try/catch), show the invitation card and a
  **"Sign in with ORCID to accept"** button (`orcidLoginUrl()`), or a link to `/signin` when
  ORCID isn't enabled.
- `VALID`, **signed in** (any state): clear the stored token immediately on mount (the URL carries
  it now — prevents redirect loops if they navigate away), show the card with an **Accept**
  button → `acceptInvitation` → invalidate `['me']` and `['projects']` → navigate to
  `/projects/{projectId}`. A 403 (disabled) or 410 shows the error.

### `RequireAuth`
After `me` has loaded successfully and **before** the non-ACTIVE check: if
`localStorage['blixa.pendingInvite']` is set → `<Navigate to={"/invite/" + token} replace />`.
This catches the post-ORCID landing on `/projects` for both new (PENDING) and existing users.

## Data flow

```
Owner ─ POST /projects/{pid}/invitations ─► insert ─► email (To invitee, CC/Reply-To owner)

Invitee clicks <base>/invite/<token>
  InviteAcceptPage ─ GET /api/public/invitations/<token> ─► preview card
    not signed in ─► localStorage.pendingInvite = token ─► ORCID ─► lands on /projects
        RequireAuth sees pendingInvite ─► /invite/<token>
    signed in ─► clear pendingInvite ─► [Accept]
        POST /api/invitations/<token>/accept  (ActiveUserFilter prefix-exempt)
          PENDING→ACTIVE, fill blank email, add member (unless already), mark accepted
        ─► /projects/<pid>
```

## Error handling summary

| Case | Result |
|---|---|
| Bad email / role on create | 400 |
| Pending unexpired invite for same email + project | 409 |
| Non-owner manages invitations | 403 (non-member 404) |
| Unknown token (preview / accept) | 404 |
| Expired or already accepted (accept) | 410 |
| Disabled account accepts | 403 |
| Already a member accepts | 200, role unchanged, invitation consumed |
| Mail not configured | invitation created, email logged; owner uses **Copy link** |

## Testing

**Backend**
- `InvitationServiceTest` (mapper/notifier mocks): create validates email + role, rejects a
  pending duplicate (409) but allows one after expiry, non-owner 403; resend rotates token and
  extends expiry; revoke 404s on an accepted/unknown id; accept — PENDING → ACTIVE, blank email
  filled but an existing email kept, existing member's role unchanged, DISABLED 403, expired/used
  410.
- `InvitationNotifierTest`: To/CC/Reply-To, body contains project title, role article, link,
  expiry date; message block only when present; no CC when the inviter has no email.
- `EmailServiceTest` (if absent, a small one): CC/Reply-To set on the `SimpleMailMessage`.
- `InvitationIT` (Testcontainers + MockMvc): owner invites → public preview VALID → a PENDING
  ORCID user accepts through the filter prefix → user ACTIVE and a member → preview ACCEPTED,
  second accept 410.

**Frontend** (vitest + MSW; handlers in `src/test/server.ts`)
- `MembersPage`: invite modal validates + submits; pending list renders with expired badge,
  Copy link / Resend / Revoke.
- `InviteAcceptPage`: VALID signed-out stores the token + shows sign-in; signed-in clears it,
  Accept navigates to the project; EXPIRED / ACCEPTED / 404 states.
- `RequireAuth`: stored token redirects to `/invite/<token>`, including for a PENDING `me`.

## Files touched

- **New (backend):** `V11__project_invitation.sql`, `invite/ProjectInvitation.java`,
  `invite/InvitationMapper.java`, `invite/InvitationService.java`,
  `invite/InvitationController.java`, `invite/InvitationNotifier.java`, `invite/dto/*`, tests.
- **Edited (backend):** `notify/EmailService.java`, `auth/ActiveUserFilter.java`.
- **New (frontend):** `api/invitations.ts`, `invite/InviteAcceptPage.tsx` (+ test).
- **Edited (frontend):** `App.tsx` (route), `auth/RequireAuth.tsx`, `projects/MembersPage.tsx`,
  `api/types.ts`, `test/server.ts`, related tests.
- **Docs:** `backlog.md` entry.
