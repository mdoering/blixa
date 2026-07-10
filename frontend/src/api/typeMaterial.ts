import { api } from './client';

export interface TypeMaterial {
  id: number;
  usageId: number;
  citation: string | null;
  status: string | null;
  institutionCode: string | null;
  catalogNumber: string | null;
  occurrenceId: string | null;
  locality: string | null;
  country: string | null;
  collector: string | null;
  date: string | null;
  sex: string | null;
  referenceId: number | null;
  link: string | null;
  remarks: string | null;
  latitude: number | null;
  longitude: number | null;
  version: number;
}

const base = (pid: number, usageId: number) =>
  `/api/projects/${pid}/usages/${usageId}/type-material`;

export function listTypeMaterial(pid: number, usageId: number): Promise<TypeMaterial[]> {
  return api<TypeMaterial[]>(base(pid, usageId));
}
export function createTypeMaterial(
  pid: number,
  usageId: number,
  payload: Record<string, unknown>,
): Promise<TypeMaterial> {
  return api<TypeMaterial>(base(pid, usageId), { method: 'POST', json: payload });
}
export function updateTypeMaterial(
  pid: number,
  usageId: number,
  id: number,
  payload: Record<string, unknown>,
): Promise<TypeMaterial> {
  return api<TypeMaterial>(`${base(pid, usageId)}/${id}`, { method: 'PUT', json: payload });
}
export function deleteTypeMaterial(pid: number, usageId: number, id: number): Promise<void> {
  return api<void>(`${base(pid, usageId)}/${id}`, { method: 'DELETE' });
}
