import { api } from './client';
import type { CreateRefPayload, Reference, UpdateRefPayload } from './types';

export interface ListRefsParams {
  q?: string;
  yearFrom?: number;
  yearTo?: number;
  limit: number;
  offset: number;
}

// GET /references?q=&yearFrom=&yearTo=&limit=&offset= — q is native pg full-text over the citation;
// yearFrom/yearTo are inclusive bounds on the reference year (see ReferenceMapper.search).
export function listReferences(pid: number, params: ListRefsParams): Promise<Reference[]> {
  const search = new URLSearchParams();
  if (params.q) search.set('q', params.q);
  if (params.yearFrom !== undefined) search.set('yearFrom', String(params.yearFrom));
  if (params.yearTo !== undefined) search.set('yearTo', String(params.yearTo));
  search.set('limit', String(params.limit));
  search.set('offset', String(params.offset));
  return api<Reference[]>(`/api/projects/${pid}/references?${search.toString()}`);
}

// Direct URL for GET /references/export.tsv?q=&yearFrom=&yearTo= -- ALL references matching the
// current filters (no pagination) as a TSV attachment. Used as an <a href download>, not via api()
// (binary attachment stream, not JSON); mirrors export.ts#exportFileUrl.
export function referenceExportTsvUrl(
  pid: number,
  params: { q?: string; yearFrom?: number; yearTo?: number },
): string {
  const search = new URLSearchParams();
  if (params.q && params.q.trim()) search.set('q', params.q.trim());
  if (params.yearFrom !== undefined) search.set('yearFrom', String(params.yearFrom));
  if (params.yearTo !== undefined) search.set('yearTo', String(params.yearTo));
  const qs = search.toString();
  return `/api/projects/${pid}/references/export.tsv${qs ? `?${qs}` : ''}`;
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

// POST /references/import-ris — parses a RIS blob (Zotero/EndNote/Mendeley export) and creates
// every record, returns the created references. Mirrors importBibtex above.
export function importRisReferences(pid: number, ris: string): Promise<Reference[]> {
  return api<Reference[]>(`/api/projects/${pid}/references/import-ris`, {
    method: 'POST',
    json: { ris },
  });
}

// POST /references/{id}/pdf — uploads (or replaces) this reference's hosted PDF; multipart, mirrors
// the ColDP-import upload's use of the `formData` branch (see api/import.ts's startImport). Returns
// the updated reference, whose pdfUrl now points at the publicly-served file.
export function attachReferencePdf(pid: number, id: number, file: File): Promise<Reference> {
  const formData = new FormData();
  formData.append('file', file);
  return api<Reference>(`/api/projects/${pid}/references/${id}/pdf`, { method: 'POST', formData });
}

// DELETE /references/{id}/pdf — detaches (and deletes) the hosted PDF. Returns the updated
// reference, whose pdfUrl is now null.
export function removeReferencePdf(pid: number, id: number): Promise<Reference> {
  return api<Reference>(`/api/projects/${pid}/references/${id}/pdf`, { method: 'DELETE' });
}

// -- Journal-name reconciliation (ReconcileJournalsModal): facet the distinct container_title
// values in the project, then bulk-normalize a chosen set of variant spellings to one canonical
// value. Field reconciliation of a single column, not a record merge -- unrelated to
// mergeReferences (api/merge.ts).
export interface ContainerTitleFacet {
  value: string;
  count: number;
}

// GET /references/facets/container-title — distinct container_title values + counts, most-cited
// first (see ReferenceMapper.containerTitleFacet).
export function getContainerTitleFacet(pid: number): Promise<ContainerTitleFacet[]> {
  return api<ContainerTitleFacet[]>(`/api/projects/${pid}/references/facets/container-title`);
}

// POST /references/facets/container-title/merge — rewrites every reference whose container_title
// is one of `variants` to `canonical`. Returns the number of rows updated.
export function mergeContainerTitle(
  pid: number,
  canonical: string,
  variants: string[],
): Promise<{ updated: number }> {
  return api<{ updated: number }>(`/api/projects/${pid}/references/facets/container-title/merge`, {
    method: 'POST',
    json: { canonical, variants },
  });
}
