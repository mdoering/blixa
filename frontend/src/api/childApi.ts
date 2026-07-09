import { api } from './client';

// Factory for the standard child-entity CRUD surface under a usage. Matches the ChildApi<T> shape
// that ChildEntityTab consumes, so a taxon-level entity needs only its type + a resource path.
export function childApi<T>(resource: string) {
  const base = (pid: number, usageId: number) =>
    `/api/projects/${pid}/usages/${usageId}/${resource}`;
  return {
    list: (pid: number, usageId: number) => api<T[]>(base(pid, usageId)),
    create: (pid: number, usageId: number, payload: Record<string, unknown>) =>
      api<T>(base(pid, usageId), { method: 'POST', json: payload }),
    update: (pid: number, usageId: number, id: number, payload: Record<string, unknown>) =>
      api<T>(`${base(pid, usageId)}/${id}`, { method: 'PUT', json: payload }),
    remove: (pid: number, usageId: number, id: number) =>
      api<void>(`${base(pid, usageId)}/${id}`, { method: 'DELETE' }),
  };
}
