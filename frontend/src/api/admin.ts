import { api } from './client';

export interface AdminUser {
  id: number;
  username: string;
  orcid: string | null;
  displayName: string | null;
  state: string; // PENDING | ACTIVE | DISABLED
  admin: boolean;
}

export function listUsers(): Promise<AdminUser[]> {
  return api<AdminUser[]>('/api/admin/users');
}

export function setUserState(id: number, state: string): Promise<AdminUser> {
  return api<AdminUser>(`/api/admin/users/${id}/state`, { method: 'POST', json: { state } });
}

export function setUserAdmin(id: number, admin: boolean): Promise<AdminUser> {
  return api<AdminUser>(`/api/admin/users/${id}/admin`, { method: 'POST', json: { admin } });
}
