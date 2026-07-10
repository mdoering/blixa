import { api } from './client';

// The ColDP identifier-scope vocabulary (backend IdScopeController): lowercase CURIE prefixes
// like "col", "gbif", "ipni". Used to seed the Project settings page's identifier-scopes picker
// with known scopes, on top of which a project can still add free custom entries.
export function getIdScopes(): Promise<string[]> {
  return api<string[]>('/api/coldp/id-scopes');
}
