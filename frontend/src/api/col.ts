import { api } from './client';

// One row of the backend's col_match_run table (see ColMatchRunResponse): the progress/tally of one
// project-wide "Match all to COL" run. total/processed/etc. are 0 until the async job has actually
// started tallying (see ColMatchJobService.runSync), never null once the row exists.
export interface ColMatchRun {
  id: number;
  projectId: number;
  status: 'RUNNING' | 'DONE' | 'FAILED';
  total: number;
  processed: number;
  verified: number;
  added: number;
  updated: number;
  unmatched: number;
  startedAt: string | null;
  finishedAt: string | null;
  error: string | null;
}

// Kicks off the bulk job (editors only, enforced server-side) -- 202 with the freshly-inserted
// RUNNING (or, for a tiny/empty project, already-DONE) row.
export function startColMatch(pid: number): Promise<ColMatchRun> {
  return api<ColMatchRun>(`/api/projects/${pid}/col-match`, { method: 'POST' });
}

// Poll target for the run started above.
export function getColMatchRun(pid: number, runId: number): Promise<ColMatchRun> {
  return api<ColMatchRun>(`/api/projects/${pid}/col-match/${runId}`);
}
