import { ActionIcon, Box, Tooltip } from '@mantine/core';
import { IconChevronLeft, IconChevronRight } from '@tabler/icons-react';
import { useEffect, useState, type CSSProperties, type ReactNode } from 'react';

interface CollapsibleSplitProps {
  /** The collapsible pane (classification tree / search table). */
  left: ReactNode;
  /** The pane that always stays visible and takes the freed space (the TaxonDetail form). */
  right: ReactNode;
  /** Width of the left pane, as a percentage, when expanded (e.g. 42 ≈ the old 5/12 Grid span). */
  leftPercent: number;
  /** localStorage key for the collapsed flag -- distinct per page so pages collapse independently. */
  storageKey: string;
  /** Passthrough style for the left pane wrapper (e.g. the tree's maxHeight/overflow scroll box). */
  leftStyle?: CSSProperties;
}

// Read the persisted collapsed flag; anything but the literal 'true' (missing/malformed) = expanded.
function readCollapsed(storageKey: string): boolean {
  try {
    return localStorage.getItem(storageKey) === 'true';
  } catch {
    return false;
  }
}

// A two-pane split whose left pane can be collapsed to give the right pane (the form) full width.
// The collapse state is remembered per `storageKey`. Used by both TreePage and NameSearchPage; the
// right pane is shared (TaxonDetail), only the left differs (tree vs. flat search table).
export default function CollapsibleSplit({
  left,
  right,
  leftPercent,
  storageKey,
  leftStyle,
}: CollapsibleSplitProps) {
  const [collapsed, setCollapsed] = useState(() => readCollapsed(storageKey));

  useEffect(() => {
    try {
      localStorage.setItem(storageKey, String(collapsed));
    } catch {
      // Ignore storage failures (private mode / quota) -- the toggle still works in-session.
    }
  }, [storageKey, collapsed]);

  const label = collapsed ? 'Expand panel' : 'Collapse panel';

  return (
    <Box style={{ display: 'flex', alignItems: 'flex-start', gap: 'var(--mantine-spacing-md)' }}>
      {!collapsed && (
        <Box style={{ flex: `0 0 ${leftPercent}%`, minWidth: 0, ...leftStyle }}>{left}</Box>
      )}
      {/* Always-present rail so the control is reachable in both states -- in particular a collapsed
          tree can always be reopened to pick another taxon. */}
      <Tooltip label={label} withArrow>
        <ActionIcon
          variant="default"
          size="sm"
          aria-label={label}
          onClick={() => setCollapsed((c) => !c)}
        >
          {collapsed ? <IconChevronRight size={16} /> : <IconChevronLeft size={16} />}
        </ActionIcon>
      </Tooltip>
      <Box style={{ flex: 1, minWidth: 0 }}>{right}</Box>
    </Box>
  );
}
