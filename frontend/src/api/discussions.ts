import { api } from './client';

export type DiscussionStatus = 'REVIEW' | 'OPEN' | 'REJECTED' | 'RESOLVED';
export type DiscussionVisibility = 'INTERNAL' | 'PUBLIC';

// A resolved @-mention: label to show + the user's ORCID (nullable) for linking out.
export interface UserMention {
  label: string;
  orcid: string | null;
}

// Resolved inline mentions for a body: #nameID -> scientific name; @orcid/@username -> user
// (keyed by the token as written).
export interface Mentions {
  usages: Record<string, string>;
  users: Record<string, UserMention>;
}

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
  mentions?: Mentions | null; // present on the detail GET
}

export interface Comment {
  id: number;
  projectId: number;
  discussionId: number;
  body: string;
  authorId: number | null;
  authorOrcid: string | null;
  authorName: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
  mentions?: Mentions | null;
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

// Editor-only: mark a discussion INTERNAL or PUBLIC.
export function setDiscussionVisibility(
  pid: number,
  id: number,
  visibility: DiscussionVisibility,
): Promise<Discussion> {
  return api<Discussion>(`/api/projects/${pid}/discussions/${id}/visibility`, {
    method: 'POST',
    json: { visibility },
  });
}

export function deleteDiscussion(pid: number, id: number): Promise<void> {
  return api<void>(`/api/projects/${pid}/discussions/${id}`, { method: 'DELETE' });
}

// -- Comments (Phase 2) --------------------------------------------------------------------------

export function listComments(pid: number, did: number): Promise<Comment[]> {
  return api<Comment[]>(`/api/projects/${pid}/discussions/${did}/comments`);
}

export function createComment(pid: number, did: number, body: string): Promise<Comment> {
  return api<Comment>(`/api/projects/${pid}/discussions/${did}/comments`, {
    method: 'POST',
    json: { body },
  });
}

export function updateComment(
  pid: number,
  did: number,
  cid: number,
  payload: { body: string; version: number },
): Promise<Comment> {
  return api<Comment>(`/api/projects/${pid}/discussions/${did}/comments/${cid}`, {
    method: 'PUT',
    json: payload,
  });
}

export function deleteComment(pid: number, did: number, cid: number): Promise<void> {
  return api<void>(`/api/projects/${pid}/discussions/${did}/comments/${cid}`, { method: 'DELETE' });
}

// Reverse links: the discussions that mention a given name_usage (#nameID).
export function listUsageDiscussions(pid: number, usageId: number): Promise<Discussion[]> {
  return api<Discussion[]>(`/api/projects/${pid}/usages/${usageId}/discussions`);
}

// -- External-submission API token (editor-only) -------------------------------------------------

export interface DiscussionToken {
  token: string | null;
}

export function getDiscussionToken(pid: number): Promise<DiscussionToken> {
  return api<DiscussionToken>(`/api/projects/${pid}/discussion-token`);
}

export function generateDiscussionToken(pid: number): Promise<DiscussionToken> {
  return api<DiscussionToken>(`/api/projects/${pid}/discussion-token`, { method: 'POST' });
}

export function revokeDiscussionToken(pid: number): Promise<void> {
  return api<void>(`/api/projects/${pid}/discussion-token`, { method: 'DELETE' });
}

// -- Public (unauthenticated) reads of PUBLIC discussions ----------------------------------------

export function getPublicDiscussion(pid: number, id: number): Promise<Discussion> {
  return api<Discussion>(`/api/public/projects/${pid}/discussions/${id}`);
}

export function listPublicComments(pid: number, id: number): Promise<Comment[]> {
  return api<Comment[]>(`/api/public/projects/${pid}/discussions/${id}/comments`);
}
