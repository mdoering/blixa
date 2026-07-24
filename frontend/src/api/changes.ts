import { api } from './client';
import type { Change } from './types';

export interface ListChangesParams {
  discussionId?: number;
  limit: number;
  offset: number;
}

// GET /changes: the project audit log, newest-first. When discussionId is set the backend returns
// just that objective's changes (grouped-by-objective view); otherwise the whole project log.
export function listChanges(pid: number, params: ListChangesParams): Promise<Change[]> {
  const search = new URLSearchParams();
  if (params.discussionId != null) search.set('discussionId', String(params.discussionId));
  search.set('limit', String(params.limit));
  search.set('offset', String(params.offset));
  return api<Change[]>(`/api/projects/${pid}/changes?${search.toString()}`);
}
