import { api } from './client';
import type { Reference } from './types';

// Mirrors BhlConfigResponse: whether BHL tooling is usable (a configured api key). Gates the UI.
export interface BhlConfig {
  available: boolean;
}

// Mirrors BhlItem: a digitised volume candidate from a publication search.
export interface BhlItem {
  itemId: number | null;
  title: string | null;
  authors: string | null;
  year: string | null;
  url: string | null;
}

export function getBhlConfig(pid: number): Promise<BhlConfig> {
  return api<BhlConfig>(`/api/projects/${pid}/bhl/config`);
}

export function bhlPublicationSearch(pid: number, q: string): Promise<BhlItem[]> {
  return api<BhlItem[]>(`/api/projects/${pid}/bhl/publication-search?q=${encodeURIComponent(q)}`);
}

// Links the reference to a BHL item; returns the updated reference (with bhlItemId).
export function setReferenceBhlItem(pid: number, refId: number, itemId: number): Promise<Reference> {
  return api<Reference>(`/api/projects/${pid}/references/${refId}/bhl-item/${itemId}`, {
    method: 'PUT',
  });
}

export function clearReferenceBhlItem(pid: number, refId: number): Promise<void> {
  return api<void>(`/api/projects/${pid}/references/${refId}/bhl-item`, { method: 'DELETE' });
}
