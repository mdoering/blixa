import { api } from './client';

// Mirrors backend DashboardResponse (org.catalogueoflife.editor.dashboard.dto.DashboardResponse).
export interface PingItem {
  projectId: number;
  projectTitle: string;
  discussionId: number;
  title: string;
  snippet: string;
  createdAt: string;
}

export interface CountRef {
  projectId: number;
  projectTitle: string;
  count: number;
}

export interface MissingMeta {
  projectId: number;
  projectTitle: string;
  missing: string[];
}

export interface LockRef {
  projectId: number;
  projectTitle: string;
  usageId: number;
  scientificName: string | null;
  acquiredAt: string;
}

export interface TaxonRef {
  projectId: number;
  projectTitle: string;
  usageId: number;
  scientificName: string | null;
  editedAt: string;
}

export interface DashboardProjectCard {
  id: number;
  title: string;
  alias: string | null;
  role: string;
  accepted: number;
  synonyms: number;
  openIssues: number;
}

export interface Dashboard {
  pendingUsers: number | null;
  pings: { count: number; items: PingItem[] };
  reviewSubmissions: CountRef[];
  missingMetadata: MissingMeta[];
  openErrors: CountRef[];
  myLocks: LockRef[];
  recentTaxa: TaxonRef[];
  projects: DashboardProjectCard[];
}

export function getDashboard(): Promise<Dashboard> {
  return api<Dashboard>('/api/me/dashboard');
}

// Stamps the "last visited" marker so the pings count resets on the next load.
export function markDashboardSeen(): Promise<void> {
  return api<void>('/api/me/dashboard/seen', { method: 'POST' });
}
