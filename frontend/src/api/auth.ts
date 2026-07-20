import { api } from './client';
import type { Me } from './types';

export function getMe(): Promise<Me> {
  return api<Me>('/api/me');
}

// Set a custom, unique username (409 if taken, 400 if malformed). Returns the updated Me.
export function updateUsername(username: string): Promise<Me> {
  return api<Me>('/api/me/username', { method: 'PUT', json: { username } });
}

export function localLogin(username: string, password: string): Promise<void> {
  return api<void>('/api/auth/login', { method: 'POST', form: { username, password } });
}

export function logout(): Promise<void> {
  return api<void>('/api/auth/logout', { method: 'POST' });
}

export function orcidLoginUrl(): string {
  return '/oauth2/authorization/orcid';
}
