import { ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import * as locks from '../api/locks';
import type { Lock } from '../api/types';
import { HEARTBEAT_MS, useUsageLock } from './useUsageLock';

const fakeLock: Lock = {
  id: 42,
  entityType: 'name_usage',
  entityId: 10,
  userId: 1,
  username: 'alice',
  acquiredAt: '2026-01-01T00:00:00Z',
  expiresAt: '2026-01-01T00:05:00Z',
  heldByMe: true,
  taskId: null,
  taskTitle: null,
};

const wrapper = ({ children }: { children: ReactNode }) => <>{children}</>;

beforeEach(() => {
  // shouldAdvanceTime lets real wall-clock time tick the fake clock too, so testing-library's
  // waitFor (which polls via setTimeout) keeps working alongside vi.advanceTimersByTime below.
  vi.useFakeTimers({ shouldAdvanceTime: true });
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

it('acquires on claim, refreshes on heartbeat, releases on unmount', async () => {
  const acquire = vi.spyOn(locks, 'acquireLock').mockResolvedValue({ lock: fakeLock, conflict: false });
  const refresh = vi.spyOn(locks, 'refreshLock').mockResolvedValue(fakeLock);
  const release = vi.spyOn(locks, 'releaseLock').mockResolvedValue();

  const { result, unmount } = renderHook(() => useUsageLock(1, 10, true), { wrapper });

  act(() => result.current.claim());
  await waitFor(() => expect(acquire).toHaveBeenCalledWith(1, { entityType: 'name_usage', entityId: 10 }));

  act(() => {
    vi.advanceTimersByTime(HEARTBEAT_MS);
  });
  await waitFor(() => expect(refresh).toHaveBeenCalled());

  unmount();
  await waitFor(() => expect(release).toHaveBeenCalledWith(1, fakeLock.id));
});
