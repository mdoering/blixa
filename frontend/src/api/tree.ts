import { api } from './client';
import type { PathNode, TreeNode } from './types';

export interface PageParams {
  limit?: number;
  offset?: number;
  // When true, the tree also includes UNASSESSED ("provisionally accepted") nodes; default (false)
  // shows only the accepted backbone. Threaded onto every roots/children fetch.
  unassessed?: boolean;
}

function pageQuery(params: PageParams): string {
  const search = new URLSearchParams();
  if (params.limit !== undefined) search.set('limit', String(params.limit));
  if (params.offset !== undefined) search.set('offset', String(params.offset));
  if (params.unassessed) search.set('unassessed', 'true');
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

// Direct URL for GET /tree/{id}/subtree.txtree -- the accepted subtree rooted at {id} (with nested
// synonyms) as a TextTree attachment. Used as an <a href download>, not via api() (binary stream).
export function subtreeTxtreeUrl(pid: number, id: number): string {
  return `/api/projects/${pid}/tree/${id}/subtree.txtree`;
}

// PUT /tree/usages/{id}/parent -- reparents `id` under `parentId` (null makes it a root). `version`
// is the moved usage's optimistic-lock version: a stale value comes back as 409. The backend is
// cycle-safe -- it rejects a self/descendant/non-accepted parent with a 400 whose {error} message
// the caller can surface. Returns 200 with an empty body (see TreeController.move).
export function moveParent(
  pid: number,
  id: number,
  parentId: number | null,
  version: number,
): Promise<void> {
  return api<void>(`/api/projects/${pid}/tree/usages/${id}/parent`, {
    method: 'PUT',
    json: { parentId, version },
  });
}
