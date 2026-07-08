import { api } from './client';
import type { Issue } from './types';

// GET /issues filters by entityType + entityId server-side (see backend IssueController/
// IssueMapper.findByProject), scoping the result to a single entity's issues.
export function getEntityIssues(pid: number, entityType: string, entityId: number): Promise<Issue[]> {
  const params = new URLSearchParams({ entityType, entityId: String(entityId) });
  return api<Issue[]>(`/api/projects/${pid}/issues?${params.toString()}`);
}
