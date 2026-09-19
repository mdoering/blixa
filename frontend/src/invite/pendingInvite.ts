// The invitation token carried across the ORCID sign-in round-trip: the backend always lands a fresh
// login on /projects, so InviteAcceptPage stores the token before sending a signed-out visitor to
// sign in and RequireAuth resumes it afterwards. Storage can be unavailable (private mode, blocked
// site data) -- every access is guarded and a failure degrades to "no pending invite" (the invitee
// can simply click the emailed link again).
const KEY = 'blixa.pendingInvite';

export function readPendingInvite(): string | null {
  try {
    return localStorage.getItem(KEY);
  } catch {
    return null;
  }
}

export function savePendingInvite(token: string): void {
  try {
    localStorage.setItem(KEY, token);
  } catch {
    /* storage unavailable */
  }
}

export function clearPendingInvite(): void {
  try {
    localStorage.removeItem(KEY);
  } catch {
    /* storage unavailable */
  }
}
