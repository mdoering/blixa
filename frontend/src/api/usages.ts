import { api } from './client';
import type { NameUsage, UpdateUsagePayload } from './types';

export function getUsage(pid: number, id: number): Promise<NameUsage> {
  return api<NameUsage>(`/api/projects/${pid}/usages/${id}`);
}

export function updateUsage(
  pid: number,
  id: number,
  payload: UpdateUsagePayload,
): Promise<NameUsage> {
  return api<NameUsage>(`/api/projects/${pid}/usages/${id}`, { method: 'PUT', json: payload });
}

// The usages linked to `id` as synonyms (id is expected to be an accepted usage).
export function getSynonyms(pid: number, id: number): Promise<NameUsage[]> {
  return api<NameUsage[]>(`/api/projects/${pid}/usages/${id}/synonyms`);
}

// The accepted usage(s) that `id` points to (id is expected to be a synonym/misapplied usage).
export function getAccepted(pid: number, id: number): Promise<NameUsage[]> {
  return api<NameUsage[]>(`/api/projects/${pid}/usages/${id}/accepted`);
}
