import { api } from './client';

export type DiscussionStatus = 'REVIEW' | 'OPEN' | 'REJECTED' | 'RESOLVED';
export type DiscussionVisibility = 'INTERNAL' | 'PUBLIC';

export interface Discussion {
  id: number;
  projectId: number;
  title: string;
  body: string | null;
  status: DiscussionStatus;
  visibility: DiscussionVisibility;
  authorId: number | null;
  authorOrcid: string | null;
  authorName: string | null;
  createdVia: string;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface DiscussionPage {
  items: Discussion[];
  total: number;
}

export interface ListDiscussionsParams {
  q?: string;
  status?: DiscussionStatus;
  authorId?: number;
  sort?: 'created' | 'modified';
  order?: 'asc' | 'desc';
  limit: number;
  offset: number;
}

// GET /discussions — full-text q over title+body, status/author filters, created|modified sort,
// paged with a total row count (see DiscussionMapper.search/count).
export function listDiscussions(pid: number, params: ListDiscussionsParams): Promise<DiscussionPage> {
  const s = new URLSearchParams();
  if (params.q) s.set('q', params.q);
  if (params.status) s.set('status', params.status);
  if (params.authorId !== undefined) s.set('authorId', String(params.authorId));
  if (params.sort) s.set('sort', params.sort);
  if (params.order) s.set('order', params.order);
  s.set('limit', String(params.limit));
  s.set('offset', String(params.offset));
  return api<DiscussionPage>(`/api/projects/${pid}/discussions?${s.toString()}`);
}

export function getDiscussion(pid: number, id: number): Promise<Discussion> {
  return api<Discussion>(`/api/projects/${pid}/discussions/${id}`);
}

export function createDiscussion(
  pid: number,
  payload: { title: string; body: string | null },
): Promise<Discussion> {
  return api<Discussion>(`/api/projects/${pid}/discussions`, { method: 'POST', json: payload });
}

export function updateDiscussion(
  pid: number,
  id: number,
  payload: { title: string; body: string | null; version: number },
): Promise<Discussion> {
  return api<Discussion>(`/api/projects/${pid}/discussions/${id}`, { method: 'PUT', json: payload });
}

export function setDiscussionStatus(
  pid: number,
  id: number,
  status: DiscussionStatus,
): Promise<Discussion> {
  return api<Discussion>(`/api/projects/${pid}/discussions/${id}/status`, {
    method: 'POST',
    json: { status },
  });
}

export function deleteDiscussion(pid: number, id: number): Promise<void> {
  return api<void>(`/api/projects/${pid}/discussions/${id}`, { method: 'DELETE' });
}
