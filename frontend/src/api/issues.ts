import { api } from './client';
import type { Issue, IssueSummary } from './types';

// GET /issues filters by entityType + entityId server-side (see backend IssueController/
// IssueMapper.findByProject), scoping the result to a single entity's issues.
export function getEntityIssues(pid: number, entityType: string, entityId: number): Promise<Issue[]> {
  const params = new URLSearchParams({ entityType, entityId: String(entityId) });
  return api<Issue[]>(`/api/projects/${pid}/issues?${params.toString()}`);
}

export interface ListIssuesParams {
  status?: string;
  severity?: string;
  limit: number;
  offset: number;
}

// Project-wide issue list for the dashboard (optional status/severity filters, paged).
export function listIssues(pid: number, params: ListIssuesParams): Promise<Issue[]> {
  const search = new URLSearchParams();
  if (params.status) search.set('status', params.status);
  if (params.severity) search.set('severity', params.severity);
  search.set('limit', String(params.limit));
  search.set('offset', String(params.offset));
  return api<Issue[]>(`/api/projects/${pid}/issues?${search.toString()}`);
}

export function issueSummary(pid: number): Promise<IssueSummary> {
  return api<IssueSummary>(`/api/projects/${pid}/issues/summary`);
}

// action: 'accept' | 'reject' | 'reopen'
export function reviewIssue(pid: number, id: number, action: string): Promise<Issue> {
  return api<Issue>(`/api/projects/${pid}/issues/${id}/review`, { method: 'POST', json: { action } });
}

// On-demand full-project recompute; returns the fresh summary.
export function revalidate(pid: number): Promise<IssueSummary> {
  return api<IssueSummary>(`/api/projects/${pid}/revalidate`, { method: 'POST' });
}
