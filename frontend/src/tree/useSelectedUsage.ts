import { useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';

// The usage shown in the TaxonDetail pane of the Tree and Names pages lives in the URL as
// ?usage=<id>, so a selected name is a shareable link and survives switching between the two views
// (the sidebar carries the param over, see AppSidebar). Selecting replaces the history entry rather
// than pushing one, so clicking through rows doesn't bloat the back stack.
export function useSelectedUsage(): [number | null, (id: number | null) => void] {
  const [searchParams, setSearchParams] = useSearchParams();
  const raw = searchParams.get('usage');
  const parsed = raw ? Number(raw) : NaN;
  const selectedId = Number.isInteger(parsed) ? parsed : null;

  const setSelectedId = useCallback(
    (id: number | null) => {
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          if (id == null) next.delete('usage');
          else next.set('usage', String(id));
          return next;
        },
        { replace: true },
      );
    },
    [setSearchParams],
  );

  return [selectedId, setSelectedId];
}
