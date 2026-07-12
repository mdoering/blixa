import { api } from './client';

// Mirrors backend ReleaseResponse (see backend .../release table): one project release build.
// `metrics` is a free-form JSON blob (per-rank/status counts etc.), opaque to the frontend for now.
export interface Release {
  id: number;
  projectId: number;
  version: string;
  notes: string | null;
  status: 'BUILDING' | 'READY' | 'FAILED';
  nameUsageCount: number | null;
  metrics: unknown | null;
  fileName: string | null;
  fileSize: number | null;
  error: string | null;
  createdAt: string | null;
}

// Newest-first release history for a project (owner-only on the editor UI, though the endpoint
// itself just requires project membership).
export function listReleases(pid: number): Promise<Release[]> {
  return api<Release[]>(`/api/projects/${pid}/releases`);
}

// Kicks off a release build (owner-only, enforced server-side) -- 202 with the freshly-inserted
// BUILDING row. Poll listReleases (or a per-id GET, once one exists) while any row is BUILDING.
export function publishRelease(pid: number, version: string, notes?: string): Promise<Release> {
  return api<Release>(`/api/projects/${pid}/releases`, { method: 'POST', json: { version, notes } });
}

export function deleteRelease(pid: number, rid: number): Promise<void> {
  return api<void>(`/api/projects/${pid}/releases/${rid}`, { method: 'DELETE' });
}
