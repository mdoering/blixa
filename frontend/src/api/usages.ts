import { api } from './client';
import type { CreateUsagePayload, NameUsage, UpdateUsagePayload, UsagePage } from './types';

export interface SearchUsagesParams {
  q?: string;
  rank?: string;
  status?: string;
  limit: number;
  offset: number;
}

// GET /usages?q=&rank=&status=&limit=&offset= -- q is pg_trgm fuzzy, rank/status are optional
// exact filters (enum-name wire form, see UsagePage/CreateUsagePayload), ANDed server-side.
// `total` counts ALL matches for the same filters (ignoring limit/offset), driving the search
// table's server-side pagination (mantine-react-table's `rowCount`).
export function searchUsages(pid: number, params: SearchUsagesParams): Promise<UsagePage> {
  const search = new URLSearchParams();
  if (params.q) search.set('q', params.q);
  if (params.rank) search.set('rank', params.rank);
  if (params.status) search.set('status', params.status);
  search.set('limit', String(params.limit));
  search.set('offset', String(params.offset));
  return api<UsagePage>(`/api/projects/${pid}/usages?${search.toString()}`);
}

export function getUsage(pid: number, id: number): Promise<NameUsage> {
  return api<NameUsage>(`/api/projects/${pid}/usages/${id}`);
}

export function createUsage(pid: number, payload: CreateUsagePayload): Promise<NameUsage> {
  return api<NameUsage>(`/api/projects/${pid}/usages`, { method: 'POST', json: payload });
}

export function updateUsage(
  pid: number,
  id: number,
  payload: UpdateUsagePayload,
): Promise<NameUsage> {
  return api<NameUsage>(`/api/projects/${pid}/usages/${id}`, { method: 'PUT', json: payload });
}

export function deleteUsage(pid: number, id: number): Promise<void> {
  return api<void>(`/api/projects/${pid}/usages/${id}`, { method: 'DELETE' });
}

// acc -> syn (see backend NameUsageService.demote): turn an accepted usage into a synonym/misapplied
// of `acceptedId`. childrenTo/synonymsTo are required by the backend only when the node has accepted
// children / its own synonyms; version is the node's optimistic lock. Returns the updated usage.
export interface DemotePayload {
  acceptedId: number;
  status: string;
  childrenTo?: string;
  synonymsTo?: string;
  version: number;
}
export function demoteUsage(pid: number, id: number, payload: DemotePayload): Promise<NameUsage> {
  return api<NameUsage>(`/api/projects/${pid}/usages/${id}/demote`, { method: 'POST', json: payload });
}

// syn -> acc (see backend NameUsageService.promote): promote a synonym/misapplied usage to accepted
// at `parentId` (null = root), dropping its synonym links. Returns the updated usage.
export interface PromotePayload {
  parentId: number | null;
  // Pro parte: accepted ids (currently this synonym's targets) to keep as separate synonym copies.
  keepAcceptedIds?: number[];
  version: number;
}
export function promoteUsage(pid: number, id: number, payload: PromotePayload): Promise<NameUsage> {
  return api<NameUsage>(`/api/projects/${pid}/usages/${id}/promote`, { method: 'POST', json: payload });
}

// Links `synonymId` (expected to already have status SYNONYM/MISAPPLIED) as a synonym of
// `acceptedId`. No request body -- this only creates the synonym_accepted row server-side.
export function linkSynonym(pid: number, synonymId: number, acceptedId: number): Promise<void> {
  return api<void>(`/api/projects/${pid}/usages/${synonymId}/synonym-of/${acceptedId}`, {
    method: 'PUT',
  });
}

// Removes the synonym_accepted link between `synonymId` and `acceptedId` (leaves both usages).
export function unlinkSynonym(pid: number, synonymId: number, acceptedId: number): Promise<void> {
  return api<void>(`/api/projects/${pid}/usages/${synonymId}/synonym-of/${acceptedId}`, {
    method: 'DELETE',
  });
}

// The usages linked to `id` as synonyms (id is expected to be an accepted usage).
export function getSynonyms(pid: number, id: number): Promise<NameUsage[]> {
  return api<NameUsage[]>(`/api/projects/${pid}/usages/${id}/synonyms`);
}

// The accepted usage(s) that `id` points to (id is expected to be a synonym/misapplied usage).
export function getAccepted(pid: number, id: number): Promise<NameUsage[]> {
  return api<NameUsage[]>(`/api/projects/${pid}/usages/${id}/accepted`);
}

// Full replace of alternativeId, optimistic-locked (backend NameUsageService.setIdentifiers) --
// the write path a later "match to COL" feature uses to persist col:<id>. Not a partial patch:
// callers must carry over any existing entries (e.g. from the loaded usage's alternativeId) they
// want to keep.
export function updateIdentifiers(
  pid: number,
  id: number,
  alternativeId: string[],
  version: number,
): Promise<NameUsage> {
  return api<NameUsage>(`/api/projects/${pid}/usages/${id}/identifiers`, {
    method: 'PUT',
    json: { alternativeId, version },
  });
}
