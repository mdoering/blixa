import { api } from './client';

export interface PublicProjectSummary {
  id: number;
  title: string;
  alias: string | null;
  description: string | null;
  latestVersion: string | null;
  latestReleasedAt: string | null;
  nameUsageCount: number | null;
}

export interface PublicContributor {
  name: string | null;
  orcid: string | null;
  role: string;
}

export interface PublicRelease {
  id: number;
  version: string;
  notes: string | null;
  createdAt: string | null;
  fileName: string | null;
  fileSize: number | null;
  nameUsageCount: number | null;
  metrics: unknown | null;
  downloadUrl: string;
}

export interface PublicProjectInfo {
  id: number;
  title: string;
  alias: string | null;
  description: string | null;
  license: string | null;
  nomCode: string | null;
  geographicScope: string | null;
  taxonomicScope: string | null;
  contributors: PublicContributor[];
  metrics: unknown | null;
  releases: PublicRelease[];
}

export function getPublicProjects(): Promise<PublicProjectSummary[]> {
  return api<PublicProjectSummary[]>('/api/public/projects');
}

export function getPublicProject(idOrAlias: string): Promise<PublicProjectInfo> {
  return api<PublicProjectInfo>(`/api/public/projects/${idOrAlias}`);
}

// Releases already carry a server-computed `downloadUrl`, but this mirrors that route so callers
// (e.g. a project card that only has ids, not the full PublicRelease) can build the link directly.
export function publicReleaseDownloadUrl(projectId: number, releaseId: number): string {
  return `/api/public/projects/${projectId}/releases/${releaseId}/download`;
}
