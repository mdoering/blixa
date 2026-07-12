import { useEffect, useRef, useState } from 'react';
import { acquireLock, refreshLock, releaseLock } from '../api/locks';
import type { Lock } from '../api/types';

export const HEARTBEAT_MS = 120_000; // < the 300s server TTL

export function useUsageLock(pid: number, usageId: number | null, enabled: boolean) {
  const [mine, setMine] = useState<Lock | null>(null);
  const [holder, setHolder] = useState<Lock | null>(null);
  const heldRef = useRef<Lock | null>(null);

  // release + reset whenever the target usage changes, the hook disables, or on unmount
  useEffect(() => {
    return () => {
      const h = heldRef.current;
      if (h?.heldByMe) releaseLock(pid, h.id).catch(() => {});
      heldRef.current = null;
      setMine(null);
      setHolder(null);
    };
  }, [pid, usageId, enabled]);

  // heartbeat while we hold our own lock
  useEffect(() => {
    if (!mine) return;
    const t = setInterval(() => {
      refreshLock(pid, mine.id)
        .then((l) => {
          heldRef.current = l;
          setMine(l);
        })
        .catch(() => {});
    }, HEARTBEAT_MS);
    return () => clearInterval(t);
  }, [pid, mine]);

  function claim() {
    if (!enabled || usageId == null || heldRef.current) return;
    acquireLock(pid, { entityType: 'name_usage', entityId: usageId })
      .then(({ lock, conflict }) => {
        heldRef.current = lock;
        if (conflict) setHolder(lock);
        else setMine(lock);
      })
      .catch(() => {});
  }

  return { mine, holder, claim };
}
