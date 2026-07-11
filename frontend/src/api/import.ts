import { api } from './client';

// One row of the backend's import_run table (see ImportRunResponse): the progress/result of one
// "Import ColDP" run, which -- unlike export/col-match, which act on an existing project --
// creates a brand-new project as a side effect (projectId is null until that project exists,
// i.e. while still RUNNING).
export interface ImportRun {
  id: number;
  projectId: number | null;
  status: 'RUNNING' | 'DONE' | 'FAILED';
  sourceName: string | null;
  preserveIds: boolean;
  idScope: string | null;
  nameUsageCount: number;
  referenceCount: number;
  authorCount: number;
  issues: { entity: string; sourceId: string | null; message: string }[];
  startedAt: string | null;
  finishedAt: string | null;
  error: string | null;
}

// Kicks off the import job (any authenticated user; they become OWNER of the newly-created
// project) -- 202 with the freshly-inserted RUNNING row. Multipart upload: the .zip goes in the
// `file` part, preserveIds/idScope as plain form fields (idScope only sent when set -- the
// backend requires it iff preserveIds is true).
export function startImport(
  file: File,
  preserveIds: boolean,
  idScope?: string,
): Promise<ImportRun> {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('preserveIds', String(preserveIds));
  if (idScope) formData.append('idScope', idScope);
  return api<ImportRun>('/api/projects/import', { method: 'POST', formData });
}

// Poll target for the run started above.
export function getImportRun(runId: number): Promise<ImportRun> {
  return api<ImportRun>(`/api/projects/import/${runId}`);
}

// Latest-run view: the most recent import run started by the current user, or null if none has
// ever been started. The backend returns 204 No Content for "none" -- the shared `api()` client
// (client.ts) already turns a 204 into `undefined` before this ever sees a body, so the `?? null`
// below just normalizes that `undefined` into an explicit `null` for callers.
export function getLatestImport(): Promise<ImportRun | null> {
  return api<ImportRun | undefined>('/api/projects/import/latest').then((run) => run ?? null);
}
