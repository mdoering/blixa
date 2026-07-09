import { api } from './client';
import type { Change, Task } from './types';

export interface ListChangesParams {
  taskId?: number;
  limit: number;
  offset: number;
}

// GET /changes: the project audit log, newest-first. When taskId is set the backend returns just
// that task's changes (grouped-by-task view); otherwise the whole project log.
export function listChanges(pid: number, params: ListChangesParams): Promise<Change[]> {
  const search = new URLSearchParams();
  if (params.taskId != null) search.set('taskId', String(params.taskId));
  search.set('limit', String(params.limit));
  search.set('offset', String(params.offset));
  return api<Change[]>(`/api/projects/${pid}/changes?${search.toString()}`);
}

// The project's tasks — drives the History task filter.
export function listTasks(pid: number): Promise<Task[]> {
  return api<Task[]>(`/api/projects/${pid}/tasks`);
}
