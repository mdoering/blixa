import { api } from './client';
import { isSignedOut } from '../auth/signedOut';
import type { Me } from './types';

export function getMe(): Promise<Me> {
  return api<Me>('/api/me');
}

// Set a custom, unique username (409 if taken, 400 if malformed). Returns the updated Me.
export function updateUsername(username: string): Promise<Me> {
  return api<Me>('/api/me/username', { method: 'PUT', json: { username } });
}

// Set/update the signed-in user's contact email (400 if malformed). Returns the updated Me.
export function updateEmail(email: string): Promise<Me> {
  return api<Me>('/api/me/email', { method: 'PUT', json: { email } });
}

// A pending applicant submits their required email + optional message. Returns the updated Me.
export function submitApplication(email: string, note: string): Promise<Me> {
  return api<Me>('/api/me/application', { method: 'PUT', json: { email, note } });
}

export function localLogin(username: string, password: string): Promise<void> {
  return api<void>('/api/auth/login', { method: 'POST', form: { username, password } });
}

export function logout(): Promise<void> {
  return api<void>('/api/auth/logout', { method: 'POST' });
}

// After an explicit sign-out, ask ORCID to re-authenticate: our logout only ends the Blixa session,
// and ORCID's own session would otherwise sign the same person straight back in.
export function orcidLoginUrl(): string {
  return isSignedOut() ? '/oauth2/authorization/orcid?prompt=login' : '/oauth2/authorization/orcid';
}
