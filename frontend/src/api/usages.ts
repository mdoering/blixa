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

// POST /usages/bulk-status -- change the taxonomic status of several usages at once. The backend
// only accepts parent-preserving transitions (accepted<->unassessed, synonym<->misapplied) and
// rejects anything else with 400; returns how many usages were actually changed.
export function bulkChangeStatus(
  pid: number,
  ids: number[],
  status: string,
): Promise<{ changed: number }> {
  return api<{ changed: number }>(`/api/projects/${pid}/usages/bulk-status`, {
    method: 'POST',
    json: { ids, status },
  });
}

export type DeleteMode = 'FOCAL_ONLY' | 'WITH_SYNONYMS' | 'SUBTREE';

// mode: FOCAL_ONLY (default) | WITH_SYNONYMS | SUBTREE; reparentTo optionally overrides where the
// focal's accepted children move on the non-subtree modes (default = the grandparent).
export function deleteUsage(
  pid: number,
  id: number,
  opts?: { mode?: DeleteMode; reparentTo?: number | null },
): Promise<void> {
  const search = new URLSearchParams();
  if (opts?.mode) search.set('mode', opts.mode);
  if (opts?.reparentTo != null) search.set('reparentTo', String(opts.reparentTo));
  const qs = search.toString();
  return api<void>(`/api/projects/${pid}/usages/${id}${qs ? `?${qs}` : ''}`, { method: 'DELETE' });
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

// Matches a single usage against the published COL checklist (backend ColMatchService, GET
// .../col-match) -- best match first (matchType = the CLB response's overall match type, e.g.
// EXACT), followed by each of its alternatives (matchType "ALTERNATIVE"). Empty array when
// unmatched. `classification` (root " > " leaf) disambiguates homonyms in the match UI.
export interface ColMatchCandidate {
  colId: string;
  name: string;
  authorship: string | null;
  rank: string | null;
  status: string | null;
  matchType: string;
  classification: string | null;
}
export function colMatch(pid: number, usageId: number): Promise<ColMatchCandidate[]> {
  return api<ColMatchCandidate[]>(`/api/projects/${pid}/usages/${usageId}/col-match`);
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

// Full replace of reference_id (the usage's taxonomic references), optimistic-locked (backend
// NameUsageService.setReferences) -- the References tab's add-existing/remove write path. Not a
// partial patch: callers must carry over any existing ids they want to keep.
export function setUsageReferences(
  pid: number,
  id: number,
  referenceIds: number[],
  version: number,
): Promise<NameUsage> {
  return api<NameUsage>(`/api/projects/${pid}/usages/${id}/references`, {
    method: 'PUT',
    json: { referenceIds, version },
  });
}

// Creates a type=webpage reference from a URL (server-side title fetch, SSRF-guarded on the
// backend) and appends it to the usage's reference_id[] (backend NameUsageService.addWebReference).
// Returns the updated usage.
export function addWebReference(pid: number, id: number, url: string): Promise<NameUsage> {
  return api<NameUsage>(`/api/projects/${pid}/usages/${id}/web-reference`, {
    method: 'POST',
    json: { url },
  });
}

// --- Homotypic grouping (see backend name/homotypy) ---
export interface SynEntry {
  id: number;
  scientificName: string | null;
  authorship: string | null;
  rank: string | null;
  status: string | null;
  formattedName: string | null;
}
export interface Synonymy {
  homotypic: SynEntry[];
  heterotypicGroups: SynEntry[][];
  misapplied: SynEntry[];
}
export interface ProposedRelation {
  usageId: number;
  relatedUsageId: number;
  type: string;
  alreadyExists: boolean;
}
export interface ProposedGroup {
  basionymUsageId: number | null;
  memberUsageIds: number[];
  relations: ProposedRelation[];
}
export interface HomotypyProposal {
  groups: ProposedGroup[];
}
export interface ApplyRelation {
  usageId: number;
  relatedUsageId: number;
  type: string;
}

export function getSynonymy(pid: number, id: number): Promise<Synonymy> {
  return api<Synonymy>(`/api/projects/${pid}/usages/${id}/synonymy`);
}
export function detectHomotypic(pid: number, id: number): Promise<HomotypyProposal> {
  return api<HomotypyProposal>(`/api/projects/${pid}/usages/${id}/homotypic/detect`);
}
export function applyHomotypic(pid: number, id: number, relations: ApplyRelation[]): Promise<Synonymy> {
  return api<Synonymy>(`/api/projects/${pid}/usages/${id}/homotypic/apply`, {
    method: 'POST',
    json: { relations },
  });
}

// --- Side 2: homotypic consolidation ---
export interface AcceptedCandidate {
  id: number;
  formattedName: string | null;
  descendantCount: number;
}
export interface ConflictMember {
  id: number;
  formattedName: string | null;
  status: string;
  acceptedTargetIds: number[];
  proParte: boolean;
  dualStatus: boolean;
  version: number;
}
export interface ConflictCluster {
  accepted: AcceptedCandidate[];
  members: ConflictMember[];
  suggestedSurvivorId: number | null;
  hasExceptions: boolean;
  relations: ProposedRelation[];
}
export interface LoserRef {
  acceptedId: number;
  version: number;
}

export function getHomotypicConflicts(pid: number, rootId: number): Promise<ConflictCluster[]> {
  return api<ConflictCluster[]>(`/api/projects/${pid}/usages/${rootId}/homotypic/conflicts`);
}
export function consolidateHomotypic(
  pid: number,
  survivorId: number,
  body: { losers: LoserRef[]; repoint: number[]; relations: ApplyRelation[] },
): Promise<Synonymy> {
  return api<Synonymy>(`/api/projects/${pid}/usages/${survivorId}/homotypic/consolidate`, {
    method: 'POST',
    json: body,
  });
}
