import type { Role } from '../api/types';

export const ROLES: Role[] = ['owner', 'editor', 'viewer'];
export const ROLE_DATA = ROLES.map((r) => ({ value: r, label: r }));

// "an owner" / "an editor" / "a viewer" -- for invitation copy.
export function roleWithArticle(role: Role): string {
  return `${role === 'viewer' ? 'a' : 'an'} ${role}`;
}
