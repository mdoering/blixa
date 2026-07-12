import { api } from './client';

export interface JoinRequest {
  id: number;
  orcid: string;
  name: string | null;
  message: string | null;
  createdAt: string;
}

export interface JoinRequestBody {
  orcid: string;
  name?: string;
  message?: string;
}

// Unauthenticated: a visitor on a public project page requests to join by submitting their ORCID.
export function requestJoin(idOrAlias: string, body: JoinRequestBody): Promise<void> {
  return api<void>(`/api/public/projects/${idOrAlias}/join`, { method: 'POST', json: body });
}

export function listJoinRequests(pid: number): Promise<JoinRequest[]> {
  return api<JoinRequest[]>(`/api/projects/${pid}/join-requests`);
}

export async function joinRequestCount(pid: number): Promise<number> {
  const res = await api<{ count: number }>(`/api/projects/${pid}/join-requests/count`);
  return res.count;
}

export function dismissJoinRequest(pid: number, id: number): Promise<void> {
  return api<void>(`/api/projects/${pid}/join-requests/${id}`, { method: 'DELETE' });
}
