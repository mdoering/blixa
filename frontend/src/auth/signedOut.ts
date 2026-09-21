// Remembers that the user explicitly signed out of Blixa, so the next ORCID sign-in asks ORCID to
// re-authenticate (prompt=login, see orcidLoginUrl) instead of silently reusing ORCID's own still-
// active session. localStorage (not session) so it survives closing the tab; cleared once signed in.
const KEY = 'blixa:signedOut';

export function markSignedOut(): void {
  try {
    localStorage.setItem(KEY, '1');
  } catch {
    // storage unavailable (private mode etc.) -- sign-in just won't force re-authentication
  }
}

export function clearSignedOut(): void {
  try {
    localStorage.removeItem(KEY);
  } catch {
    // ignore
  }
}

export function isSignedOut(): boolean {
  try {
    return localStorage.getItem(KEY) === '1';
  } catch {
    return false;
  }
}
