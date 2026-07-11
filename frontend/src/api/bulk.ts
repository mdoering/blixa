import { api } from './client';

export interface BulkPreviewNode {
  name: string;
  rank: string;
  status: string;
  extinct: boolean;
  duplicate: boolean;
  children: BulkPreviewNode[];
  synonyms: BulkPreviewNode[];
}

export interface BulkPreview {
  valid: boolean;
  error: string | null;
  total: number;
  accepted: number;
  synonyms: number;
  duplicates: number;
  nodes: BulkPreviewNode[];
}

export interface BulkInsertResult {
  created: number;
  synonymsLinked: number;
  targetId: number;
}

export interface BulkBody {
  targetId: number;
  mode: 'children' | 'synonyms';
  text: string;
}

export function previewBulk(pid: number, body: BulkBody): Promise<BulkPreview> {
  return api<BulkPreview>(`/api/projects/${pid}/usages/bulk/preview`, { method: 'POST', json: body });
}

export function insertBulk(pid: number, body: BulkBody): Promise<BulkInsertResult> {
  return api<BulkInsertResult>(`/api/projects/${pid}/usages/bulk`, { method: 'POST', json: body });
}
