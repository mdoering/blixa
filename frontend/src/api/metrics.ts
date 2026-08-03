import { api } from './client';

// Mirrors ReleaseMetricsService.compute output (GET /api/projects/{pid}/metrics).
export interface ProjectMetrics {
  acceptedByRank: Record<string, number>;
  synonymsByRank: Record<string, number>;
  supplementary: Record<string, number>;
  changesSinceLastRelease: Record<string, number>;
  contributions: { userId: number; name: string | null; orcid: string | null; count: number }[];
}

export function getProjectMetrics(pid: number): Promise<ProjectMetrics> {
  return api<ProjectMetrics>(`/api/projects/${pid}/metrics`);
}
