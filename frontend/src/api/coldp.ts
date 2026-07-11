import { api } from './client';

// The ColDP identifier-scope vocabulary (backend IdScopeController): lowercase CURIE prefixes
// like "col", "gbif", "ipni". Used to seed the Project settings page's identifier-scopes picker
// with known scopes, on top of which a project can still add free custom entries.
export function getIdScopes(): Promise<string[]> {
  return api<string[]>('/api/coldp/id-scopes');
}

// The enum vocabularies used to constrain the taxon editing form's dropdowns (backend
// VocabController). Values are in the exact stored/returned form so they round-trip: `ranks` are
// lower-case, the rest are the upper-case enum name.
export interface Vocab {
  ranks: string[];
  nomStatus: string[];
  gender: string[];
  environment: string[];
}

export function getVocab(): Promise<Vocab> {
  return api<Vocab>('/api/coldp/vocab');
}
