# Direct ORCID sign-in

**Date:** 2026-08-14
**Status:** Design — awaiting review

## Problem

When ORCID is enabled, signing in takes two clicks:

1. Click **"Sign in"** in the header → navigates to `/signin`.
2. On `/signin` (`LoginPage`), click **"Sign in with ORCID"** → `/oauth2/authorization/orcid`.

The intermediate `/signin` page adds nothing for ORCID users — it is a button that
just forwards to ORCID. We want the explicit sign-in affordances to go **straight to
ORCID**, reserving `/signin` for local (username/password) logins and as a safe fallback.

## Goal

When `orcidEnabled` is true:

- The header **"Sign in"** link goes directly to `/oauth2/authorization/orcid`.
- The **protected-route redirect** (a logged-out user hitting a `RequireAuth` route)
  goes directly to `/oauth2/authorization/orcid`.

When `orcidEnabled` is false, both keep going to `/signin` (the local login form).

## Non-goals / constraints

- **`/signin` must not blindly auto-redirect to ORCID.** After logout our session
  cookie is cleared, but the user's ORCID SSO session at orcid.org is not. An
  unconditional `/signin` → ORCID bounce would silently re-authenticate the user and
  make logout appear broken. So the redirect-to-ORCID logic lives at the *explicit
  sign-in affordances*, never inside `/signin` itself.
- **Post-logout and pending-approval stay on `/signin`** (unchanged). `/signin` keeps
  its existing "Sign in with ORCID" button as the fallback for those cases and for
  anyone landing on the URL directly. So `/signin` remains fully functional in ORCID
  mode — ORCID users just don't normally pass through it.
- **No `returnTo` handling.** There is none today (RequireAuth already forgets the
  target route), so this change introduces no regression and adds no new deep-link
  memory. Out of scope.

## Design

`orcidEnabled` is only known via `GET /api/config` (async). The change is to consult
that config at the two entry points and pick the destination.

### 1. Shared config hook — `useConfig()`

Extract the query that `LoginPage` already runs inline into a reusable hook
(`src/api/config.ts` or `src/auth/useConfig.ts`):

```ts
export function useConfig() {
  return useQuery({ queryKey: ['config'], queryFn: getConfig, staleTime: Infinity });
}
```

`LoginPage`, `PublicLayout`, and the new `SignInRedirect` all use it. `staleTime:
Infinity` + a single query key means it is fetched once and shared.

**Fetch failure is treated as "not ORCID":** if the config query errors, entry points
fall back to `/signin`. That keeps the app usable (local login still works) rather than
dead-ending on a failed ORCID bounce.

### 2. Header "Sign in" link (`PublicLayout`)

Consult `useConfig()`:

- `orcidEnabled === true` → render a plain anchor: `<Anchor href={orcidLoginUrl()}>Sign
  in</Anchor>`. A plain `href` (not react-router `<Link>`) is required because
  `/oauth2/authorization/orcid` is a backend route, so it must be a full-page navigation.
- otherwise (loading, error, or `orcidEnabled === false`) → the current react-router
  link: `<Anchor component={Link} to="/signin">Sign in</Anchor>`.

The visible text stays **"Sign in"** in both cases; only the destination changes.

### 3. Protected-route redirect (`RequireAuth`)

Today: `if (isError || !data) return <Navigate to="/signin" replace />;`

Replace the `<Navigate to="/signin">` with a small `<SignInRedirect />` component that:

- reads `useConfig()`;
- while config is loading → renders the existing centered `<Loader />`;
- `orcidEnabled === true` → `window.location.assign(orcidLoginUrl())` from an effect,
  rendering a `<Loader />` while the browser navigates away (guarded so it fires once);
- otherwise → `<Navigate to="/signin" replace />` (current behaviour).

Extracting `SignInRedirect` keeps `RequireAuth` readable and makes the branch unit-
testable in isolation. The ACTIVE-state gate in `RequireAuth` is unchanged.

### 4. `/signin` (`LoginPage`) — unchanged behaviour

`LoginPage` keeps rendering the "Sign in with ORCID" button when `orcidEnabled` and the
local form otherwise. Only refactor: swap its inline `useQuery(['config'])` for the
shared `useConfig()` hook. This preserves the logout / pending-approval / direct-visit
fallback and avoids the re-login loop.

### 5. Untouched

`AppLayout` logout (`navigate('/signin')`) and `PendingApprovalPage`
(`window.location.assign('/signin')`) are intentionally left pointing at `/signin`.

## Data flow

```
Header "Sign in"  ─ orcidEnabled? ─ yes ─►  GET /oauth2/authorization/orcid  (full-page)
                                   no  ─►  react-router /signin (local form)

logged-out → protected route
  RequireAuth → SignInRedirect ─ orcidEnabled? ─ yes ─► window.location.assign(orcid)
                                                no  ─► <Navigate to="/signin">

logout / pending-approval / direct visit  ─►  /signin  (ORCID button OR local form)
```

## Testing (vitest + @testing-library + MSW)

- **`PublicLayout`** — header link is `href="/oauth2/authorization/orcid"` when
  `orcidEnabled: true`; is a `/signin` link when `orcidEnabled: false`. (MSW default is
  already `orcidEnabled: true`.)
- **`SignInRedirect`** — with `orcidEnabled: true` and an unauthenticated `me`, it calls
  `window.location.assign('/oauth2/authorization/orcid')` (spied); with `orcidEnabled:
  false` it renders / navigates to `/signin`.
- **`LoginPage`** — existing tests unchanged (button + form fallbacks still present).
- **`AppRouting`** — existing "anonymous visitor" test still passes: the header link is
  still named exactly "Sign in" (only its `href` changed). Optionally tighten it to
  assert the ORCID `href`.

## Files touched

- `src/api/config.ts` (or new `src/auth/useConfig.ts`) — add `useConfig()`.
- `src/components/PublicLayout.tsx` — config-aware header link.
- `src/auth/RequireAuth.tsx` — delegate the redirect to `SignInRedirect`.
- `src/auth/SignInRedirect.tsx` — **new** redirect component.
- `src/auth/LoginPage.tsx` — use shared `useConfig()` (no behaviour change).
- Tests: `PublicLayout.test.tsx` (new), `SignInRedirect.test.tsx` (new), possibly
  `AppRouting.test.tsx` (tighten).
