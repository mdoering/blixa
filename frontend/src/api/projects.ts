import { api } from './client';
import type { CreateProjectPayload, Member, Project, Role, UpdateMetadataPayload } from './types';

export function listProjects(): Promise<Project[]> {
  return api<Project[]>('/api/projects');
}
export function getProject(id: number): Promise<Project> {
  return api<Project>(`/api/projects/${id}`);
}
export function createProject(payload: CreateProjectPayload): Promise<Project> {
  return api<Project>('/api/projects', { method: 'POST', json: payload });
}
export function updateMetadata(id: number, payload: UpdateMetadataPayload): Promise<Project> {
  return api<Project>(`/api/projects/${id}/metadata`, { method: 'PUT', json: payload });
}
export function listMembers(id: number): Promise<Member[]> {
  return api<Member[]>(`/api/projects/${id}/members`);
}
export function setMember(id: number, username: string, role: Role): Promise<void> {
  return api<void>(`/api/projects/${id}/members`, { method: 'PUT', json: { username, role } });
}
export function removeMember(id: number, userId: number): Promise<void> {
  return api<void>(`/api/projects/${id}/members/${userId}`, { method: 'DELETE' });
}
export function deleteProject(id: number): Promise<void> {
  return api<void>(`/api/projects/${id}`, { method: 'DELETE' });
}
export function setPublic(id: number, isPublic: boolean): Promise<void> {
  return api<void>(`/api/projects/${id}/public`, { method: 'PUT', json: { public: isPublic } });
}
