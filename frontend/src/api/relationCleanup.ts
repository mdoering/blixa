import { api } from './client';

export interface PairName {
  id: number;
  label: string;
  status: string;
}

export interface JudgedRelation {
  id: number;
  version: number;
  type: string | null;
  usageId: number;
  relatedUsageId: number | null;
  referenceId: number | null;
  page: string | null;
  remarks: string | null;
  issues: string[];
  remove: boolean;
  duplicate: boolean;
  preselected: boolean;
}

export interface RelationConflictPair {
  n1: PairName;
  n2: PairName;
  relations: JudgedRelation[];
}

// Scan a subtree (rootId) or the whole project for conflicting name relations.
export function getRelationConflicts(pid: number, rootId?: number): Promise<RelationConflictPair[]> {
  const q = rootId != null ? `?rootId=${rootId}` : '';
  return api<RelationConflictPair[]>(`/api/projects/${pid}/name-relations/conflicts${q}`);
}

// Delete exactly the listed relations (each with the version the editor saw); 409 if any changed.
export function cleanupRelations(
  pid: number,
  relations: { id: number; version: number }[],
): Promise<{ deleted: number }> {
  return api<{ deleted: number }>(`/api/projects/${pid}/name-relations/cleanup`, {
    method: 'POST',
    json: { relations },
  });
}
