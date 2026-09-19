import { api } from './client';
import type { Role } from './types';

export interface Invitation {
  id: number;
  email: string;
  role: Role;
  message: string | null;
  invitedBy: string | null;
  createdAt: string;
  expiresAt: string;
  expired: boolean;
  acceptUrl: string;
}

export interface InvitationPreview {
  projectTitle: string;
  invitedBy: string | null;
  role: Role;
  message: string | null;
  status: 'VALID' | 'EXPIRED' | 'ACCEPTED';
}

export interface InvitationBody {
  email: string;
  role: Role;
  message?: string;
}

// Owner-only: a project's pending (not yet accepted) email invitations, expired ones included.
export function listInvitations(pid: number): Promise<Invitation[]> {
  return api<Invitation[]>(`/api/projects/${pid}/invitations`);
}

// Creates the invitation and emails it (409 if a live one exists for that address).
export function createInvitation(pid: number, body: InvitationBody): Promise<Invitation> {
  return api<Invitation>(`/api/projects/${pid}/invitations`, { method: 'POST', json: body });
}

// New link + fresh 30-day window, emailed again; the old link stops working.
export function resendInvitation(pid: number, id: number): Promise<Invitation> {
  return api<Invitation>(`/api/projects/${pid}/invitations/${id}/resend`, { method: 'POST' });
}

export function revokeInvitation(pid: number, id: number): Promise<void> {
  return api<void>(`/api/projects/${pid}/invitations/${id}`, { method: 'DELETE' });
}

// Unauthenticated: what an invitation link is for, shown before sign-in.
export function getInvitationPreview(token: string): Promise<InvitationPreview> {
  return api<InvitationPreview>(`/api/public/invitations/${encodeURIComponent(token)}`);
}

export function acceptInvitation(token: string): Promise<{ projectId: number }> {
  return api<{ projectId: number }>(`/api/invitations/${encodeURIComponent(token)}/accept`, {
    method: 'POST',
  });
}
