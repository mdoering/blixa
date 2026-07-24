import { api } from './client';

// One GBIF type-specimen occurrence mapped onto TypeMaterial fields (+ alreadyImported). The Types
// tab imports a ticked candidate by POSTing these fields to the type-material create endpoint.
export interface GbifTypeCandidate {
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
  link: string | null;
  latitude: number | null;
  longitude: number | null;
  alreadyImported: boolean;
}

export interface GbifTypes {
  name: string | null;
  colId: string | null; // null = the name couldn't be matched to COL
  truncated: boolean;
  candidates: GbifTypeCandidate[];
}

// GET /usages/{id}/gbif-types: the usage's GBIF type specimens (resolved via COL). Read-only.
export function getGbifTypes(pid: number, usageId: number): Promise<GbifTypes> {
  return api<GbifTypes>(`/api/projects/${pid}/usages/${usageId}/gbif-types`);
}
