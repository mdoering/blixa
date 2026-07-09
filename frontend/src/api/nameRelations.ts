import { api } from './client';

export interface NameRelation {
  id: number;
  usageId: number;
  relatedUsageId: number | null;
  relatedName: string | null;
  type: string | null;
  referenceId: number | null;
  page: string | null;
  remarks: string | null;
  version: number;
}

const base = (pid: number, usageId: number) => `/api/projects/${pid}/usages/${usageId}/relations`;

export function listNameRelations(pid: number, usageId: number): Promise<NameRelation[]> {
  return api<NameRelation[]>(base(pid, usageId));
}
export function createNameRelation(
  pid: number,
  usageId: number,
  payload: Record<string, unknown>,
): Promise<NameRelation> {
  return api<NameRelation>(base(pid, usageId), { method: 'POST', json: payload });
}
export function updateNameRelation(
  pid: number,
  usageId: number,
  id: number,
  payload: Record<string, unknown>,
): Promise<NameRelation> {
  return api<NameRelation>(`${base(pid, usageId)}/${id}`, { method: 'PUT', json: payload });
}
export function deleteNameRelation(pid: number, usageId: number, id: number): Promise<void> {
  return api<void>(`${base(pid, usageId)}/${id}`, { method: 'DELETE' });
}
