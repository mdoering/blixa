import { api } from './client';

// -- Shared taxon property keys (PropertyKeysModal + the PropertyTab autocomplete): a project-wide
// view of every property key (the union of keys actually used in property rows and keys defined in
// property_key), with usage counts and optional descriptions, plus reconciliation of variant
// spellings. Mirrors the journal-name reconciliation in api/references.ts.
export interface PropertyKeyInfo {
  key: string;
  count: number;
  description: string | null;
}

// GET /property-keys — used ∪ defined keys, each with its count (0 for defined-but-unused) and
// description (null for used-but-undefined), most-used first.
export function getPropertyKeys(pid: number): Promise<PropertyKeyInfo[]> {
  return api<PropertyKeyInfo[]>(`/api/projects/${pid}/property-keys`);
}

// PUT /property-keys — define a standard key / edit its description (upsert). The key travels in the
// body, not the path: free-form keys (spaces, slashes, dots) can't be a URL path segment.
export function definePropertyKey(
  pid: number,
  key: string,
  description: string | null,
): Promise<PropertyKeyInfo> {
  return api<PropertyKeyInfo>(`/api/projects/${pid}/property-keys`, {
    method: 'PUT',
    json: { key, description },
  });
}

// DELETE /property-keys?key=… — removes only the definition (never property rows). Key as a query
// param for the same path-encoding reason as the PUT body.
export function deletePropertyKey(pid: number, key: string): Promise<void> {
  return api<void>(`/api/projects/${pid}/property-keys?key=${encodeURIComponent(key)}`, {
    method: 'DELETE',
  });
}

// POST /property-keys/merge — rewrites every property whose key is one of `variants` to `canonical`
// and folds the variants' definitions into it. Returns the number of property rows updated.
export function mergePropertyKeys(
  pid: number,
  canonical: string,
  variants: string[],
): Promise<{ updated: number }> {
  return api<{ updated: number }>(`/api/projects/${pid}/property-keys/merge`, {
    method: 'POST',
    json: { canonical, variants },
  });
}
