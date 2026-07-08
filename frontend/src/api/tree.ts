import { api } from './client';
import type { PathNode, TreeNode } from './types';

export interface PageParams {
  limit?: number;
  offset?: number;
}

function pageQuery(params: PageParams): string {
  const search = new URLSearchParams();
  if (params.limit !== undefined) search.set('limit', String(params.limit));
  if (params.offset !== undefined) search.set('offset', String(params.offset));
  const s = search.toString();
  return s ? `?${s}` : '';
}

export function getRoots(pid: number, params: PageParams = {}): Promise<TreeNode[]> {
  return api<TreeNode[]>(`/api/projects/${pid}/tree/roots${pageQuery(params)}`);
}

export function getChildren(
  pid: number,
  parentId: number,
  params: PageParams = {},
): Promise<TreeNode[]> {
  return api<TreeNode[]>(`/api/projects/${pid}/tree/children/${parentId}${pageQuery(params)}`);
}

export function getPath(pid: number, id: number): Promise<PathNode[]> {
  return api<PathNode[]>(`/api/projects/${pid}/tree/path/${id}`);
}
