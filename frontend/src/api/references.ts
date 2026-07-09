import { api } from './client';
import type { CreateRefPayload, Reference, UpdateRefPayload } from './types';

export interface ListRefsParams {
  q?: string;
  limit: number;
  offset: number;
}

// GET /references?q=&limit=&offset= — q is the pg_trgm fuzzy citation search (see ReferenceMapper).
export function listReferences(pid: number, params: ListRefsParams): Promise<Reference[]> {
  const search = new URLSearchParams();
  if (params.q) search.set('q', params.q);
  search.set('limit', String(params.limit));
  search.set('offset', String(params.offset));
  return api<Reference[]>(`/api/projects/${pid}/references?${search.toString()}`);
}

export function getReference(pid: number, id: number): Promise<Reference> {
  return api<Reference>(`/api/projects/${pid}/references/${id}`);
}

export function createReference(pid: number, payload: CreateRefPayload): Promise<Reference> {
  return api<Reference>(`/api/projects/${pid}/references`, { method: 'POST', json: payload });
}

export function updateReference(pid: number, id: number, payload: UpdateRefPayload): Promise<Reference> {
  return api<Reference>(`/api/projects/${pid}/references/${id}`, { method: 'PUT', json: payload });
}

export function deleteReference(pid: number, id: number): Promise<void> {
  return api<void>(`/api/projects/${pid}/references/${id}`, { method: 'DELETE' });
}

// POST /references/resolve-doi — server fetches Crossref and returns an UNSAVED create-preview.
export function resolveDoi(pid: number, doi: string): Promise<CreateRefPayload> {
  return api<CreateRefPayload>(`/api/projects/${pid}/references/resolve-doi`, {
    method: 'POST',
    json: { doi },
  });
}

// POST /references/import-bibtex — parses + creates every entry, returns the created references.
export function importBibtex(pid: number, bibtex: string): Promise<Reference[]> {
  return api<Reference[]>(`/api/projects/${pid}/references/import-bibtex`, {
    method: 'POST',
    json: { bibtex },
  });
}
