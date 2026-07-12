import { api } from './client';
import type { IdScope } from './types';

// The ColDP identifier-scope vocabulary (backend IdScopeController): lowercase CURIE prefixes
// like "col", "gbif", "ipni", each carrying an optional title and a resolver base `link` (used by
// CurieId to render a clickable CURIE). Used to seed the Project settings page's
// identifier-scopes picker with known scopes, on top of which a project can still add free custom
// entries.
export function getIdScopes(): Promise<IdScope[]> {
  return api<IdScope[]>('/api/coldp/id-scopes');
}

// The enum vocabularies used to constrain the taxon editing form's dropdowns (backend
// VocabController). Values are in the exact stored/returned form so they round-trip: `ranks` are
// lower-case, the rest are the upper-case enum name.
// Each nomenclatural-status option carries the enum-name `value` plus both code-specific labels;
// the taxon form shows the one matching the project's nomenclatural code (zoological label for
// zoological projects, botanical label otherwise -- bacteria follow botany).
export interface NomStatusOption {
  value: string;
  botanical: string;
  zoological: string;
}

export interface Vocab {
  ranks: string[];
  nomStatus: NomStatusOption[];
  gender: string[];
  environment: string[];
  // CSL-JSON wire ids (e.g. "article-journal") for the reference `type` dropdown; the same
  // canonical form the backend persists (ReferenceService.validateType), so a picked value always
  // round-trips.
  cslTypes: string[];
}

export function getVocab(): Promise<Vocab> {
  return api<Vocab>('/api/coldp/vocab');
}
