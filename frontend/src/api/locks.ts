import { api, ApiError } from './client';
import type { Lock } from './types';

export const listLocks = (pid: number) => api<Lock[]>(`/api/projects/${pid}/locks`);

export async function acquireLock(
  pid: number,
  body: { entityType: string; entityId: number; ttlSeconds?: number },
): Promise<{ lock: Lock; conflict: boolean }> {
  try {
    const lock = await api<Lock>(`/api/projects/${pid}/locks`, { method: 'POST', json: body });
    return { lock, conflict: false };
  } catch (e) {
    // 409 = held by someone else; the client doesn't carry the parsed body on the thrown error, so
    // re-fetch listLocks and find the entity's current holder. Any other error (network, 4xx/5xx)
    // is rethrown as-is.
    if (e instanceof ApiError && e.status === 409) {
      const holder = (await listLocks(pid)).find(
        (l) => l.entityType === body.entityType && l.entityId === body.entityId,
      );
      if (holder) return { lock: holder, conflict: true };
    }
    throw e;
  }
}

export const refreshLock = (pid: number, lockId: number, ttlSeconds?: number) =>
  api<Lock>(`/api/projects/${pid}/locks/${lockId}/refresh${ttlSeconds ? `?ttlSeconds=${ttlSeconds}` : ''}`, {
    method: 'POST',
  });

export const releaseLock = (pid: number, lockId: number) =>
  api<void>(`/api/projects/${pid}/locks/${lockId}`, { method: 'DELETE' });
