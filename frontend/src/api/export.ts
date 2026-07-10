import { api } from './client';

// One row of the backend's export_run table (see ExportRunResponse): the progress/result of one
// project-wide "Export ColDP" run. fileName/fileSize/nameUsageCount/referenceCount are null until
// the async job (ColdpWriter.write) has actually finished -- see ExportRunService.run's runs.finish
// call, which is the only place that sets them.
export interface ExportRun {
  id: number;
  projectId: number;
  status: 'RUNNING' | 'DONE' | 'FAILED';
  fileName: string | null;
  fileSize: number | null;
  nameUsageCount: number | null;
  referenceCount: number | null;
  startedAt: string | null;
  finishedAt: string | null;
  error: string | null;
}

// Kicks off the export job (any project member, enforced server-side) -- 202 with the
// freshly-inserted RUNNING (or, for a tiny/fast project, already-DONE) row. Rejects with a 409
// ApiError if an export is already in progress for the project (ExportRunService.start).
export function startExport(pid: number): Promise<ExportRun> {
  return api<ExportRun>(`/api/projects/${pid}/export`, { method: 'POST' });
}

// Poll target for the run started above.
export function getExportRun(pid: number, runId: number): Promise<ExportRun> {
  return api<ExportRun>(`/api/projects/${pid}/export/${runId}`);
}

// Latest-run view (Project page, load on mount): the most recent export run for the project, or
// null if none has ever been started. The backend returns 204 No Content for "none" -- the shared
// `api()` client (client.ts) already turns a 204 into `undefined` before this ever sees a body, so
// the `?? null` below just normalizes that `undefined` into an explicit `null` for callers.
export function getLatestExport(pid: number): Promise<ExportRun | null> {
  return api<ExportRun | undefined>(`/api/projects/${pid}/export/latest`).then((run) => run ?? null);
}

// Direct download URL for a DONE run's zip (ExportRunController.file) -- used as an <a href> rather
// than routed through api(), since the response is a binary attachment stream, not JSON.
export function exportFileUrl(pid: number, runId: number): string {
  return `/api/projects/${pid}/export/${runId}/file`;
}
