import { api } from './client';

// Mirrors MergeRunResponse.status (MergeRun.java's status column, see V19__merge_run.sql):
// RUNNING while the compute-plan @Async job runs, PLANNED once the plan+metrics are stored (ready
// for review/apply), APPLYING while the apply @Async job runs, DONE/FAILED terminal.
export type MergeStatus = 'RUNNING' | 'PLANNED' | 'APPLYING' | 'DONE' | 'FAILED';

// Mirrors dto.Mode -- the apply-time reconciliation strategy for MATCHED records (see Mode.java's
// javadoc for what each one does to a matched target record; NEW records are always added
// regardless of mode).
export type MergeMode = 'OVERWRITE' | 'FILL_GAPS' | 'NEW_ONLY';

// Mirrors dto.Category -- one source record's match outcome against the target project (see
// Category.java's javadoc for what each value means).
export type MergeCategory = 'MATCHED' | 'POSSIBLE_HOMONYM' | 'POSSIBLE_FUZZY' | 'POSSIBLE' | 'NEW';

// Mirrors dto.MergeRunResponse.MergeIssue -- a per-record non-fatal problem recorded during
// compute-plan or apply (e.g. an unanchored NEW accepted name).
export interface MergeIssue {
  entity: string;
  sourceId: string | null;
  message: string;
}

// Mirrors dto.MergeRunResponse.MergeMetrics.NameCounts. "new" is a reserved word in some contexts
// but a perfectly fine object-literal/property-access key in TS -- the backend's @JsonProperty
// mapping already made it the wire name (see MergeRunResponse.java), so this just matches verbatim.
export interface MergeNameCounts {
  new: number;
  matched: number;
  possibleHomonym: number;
  possibleFuzzy: number;
}

// Mirrors dto.MergeRunResponse.MergeMetrics.ReferenceCounts.
export interface MergeReferenceCounts {
  new: number;
  matched: number;
  possible: number;
}

// Mirrors dto.MergeRunResponse.MergeMetrics -- the PLANNED run's impact summary (never present
// before PLANNED, since it's computed by the same job that produces the plan).
export interface MergeMetrics {
  names: MergeNameCounts;
  references: MergeReferenceCounts;
  newAccepted: number;
  newSynonyms: number;
  unanchored: number;
}

// Mirrors dto.MergeRunResponse -- the API-facing projection of one merge_run row. Deliberately
// omits the raw plan JSON, same as the backend -- the full mapping is paged separately via
// getMergeMapping/GET .../mapping (Task 10's review tables).
export interface MergeRun {
  id: number;
  sourceProjectId: number;
  targetProjectId: number;
  status: MergeStatus;
  mode: MergeMode | null;
  transactional: boolean | null;
  metrics: MergeMetrics | null;
  issues: MergeIssue[] | null;
  startedAt: string | null;
  plannedAt: string | null;
  finishedAt: string | null;
  error: string | null;
}

// Mirrors dto.Candidate -- one source record's match result (a name-usage or reference id, scoped
// to its own project). targetId is null for category NEW; score is 1.0 for MATCHED, null for NEW/
// POSSIBLE_HOMONYM/POSSIBLE, and the trigram similarity for POSSIBLE_FUZZY (and a fuzzy-citation
// POSSIBLE for references).
export interface Candidate {
  sourceId: string;
  category: MergeCategory;
  targetId: string | null;
  score: number | null;
}

// Mirrors dto.MappingRow -- a Candidate enriched with display labels for both sides (Task 10's
// review table). targetLabel is null when targetId is null or unresolvable.
export interface MappingRow extends Candidate {
  sourceLabel: string | null;
  targetLabel: string | null;
}

// Mirrors dto.MergeOverride -- one curator-submitted correction to a Candidate already sitting in a
// PLANNED run's stored plan (Task 10). targetId is required (and validated server-side) when
// category is MATCHED, ignored/forced null when category is NEW -- POSSIBLE_* are not valid
// override categories, only a curator's confirm/reject decisions are (see MergeOverride.java).
export interface MergeOverride {
  entity: 'name' | 'reference';
  sourceId: string;
  category: MergeCategory;
  targetId: string | null;
}

// Kicks off the compute-plan job (owner/editor on the target; any role on the source) -- 202 with
// the freshly-inserted RUNNING row.
export function startMerge(targetId: number, sourceId: number): Promise<MergeRun> {
  return api<MergeRun>(`/api/projects/${targetId}/merge?source=${sourceId}`, { method: 'POST' });
}

// Poll target for the run started above (or resumed via getLatestMerge).
export function getMergeRun(targetId: number, runId: number): Promise<MergeRun> {
  return api<MergeRun>(`/api/projects/${targetId}/merge/${runId}`);
}

// Latest-run view (load on mount/open): the most recent merge run for this target project, or null
// if none has ever been started. The backend returns 204 No Content for "none" -- the shared api()
// client (client.ts) already turns a 204 into `undefined` before this ever sees a body, so the
// `?? null` below just normalizes that `undefined` into an explicit `null` for callers.
export function getLatestMerge(targetId: number): Promise<MergeRun | null> {
  return api<MergeRun | undefined>(`/api/projects/${targetId}/merge/latest`).then(
    (run) => run ?? null,
  );
}

// Pages one entity half (name or reference) of a PLANNED run's stored plan, display-enriched, for
// the review table -- Task 10. category filters to one Category (e.g. only the POSSIBLE_* rows
// that need a curator's attention); omitted, every category is returned.
export function getMergeMapping(
  targetId: number,
  runId: number,
  entity: 'name' | 'reference',
  category?: MergeCategory,
  page = 0,
  size = 50,
): Promise<MappingRow[]> {
  const params = new URLSearchParams({ entity, page: String(page), size: String(size) });
  if (category) params.set('category', category);
  return api<MappingRow[]>(`/api/projects/${targetId}/merge/${runId}/mapping?${params.toString()}`);
}

// Submits curator corrections to a still-PLANNED run's stored plan -- Task 10. Returns the updated
// MergeRunResponse (metrics are recomputed server-side to reflect the overrides).
export function putMergeOverrides(
  targetId: number,
  runId: number,
  overrides: MergeOverride[],
): Promise<MergeRun> {
  return api<MergeRun>(`/api/projects/${targetId}/merge/${runId}/overrides`, {
    method: 'PUT',
    json: overrides,
  });
}

// The curator's go-ahead once a PLANNED run's mapping has been reviewed/overridden -- 202, fires
// the apply @Async job (same 202-now/poll-for-DONE contract as startMerge). transactional omitted
// defaults to true server-side, except a full-import (all-NEW) plan, which is always applied
// non-transactionally regardless of what's sent (see MergeApplyService.apply's javadoc).
export function applyMerge(
  targetId: number,
  runId: number,
  body: { mode: MergeMode; transactional: boolean },
): Promise<MergeRun> {
  return api<MergeRun>(`/api/projects/${targetId}/merge/${runId}/apply`, {
    method: 'POST',
    json: body,
  });
}
