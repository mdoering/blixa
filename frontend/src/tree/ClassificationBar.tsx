import { ActionIcon, Anchor, Group, Loader, Text, Tooltip } from '@mantine/core';
import { IconArrowsExchange } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import type { ReactNode } from 'react';
import { getPath } from '../api/tree';
import type { NameUsage } from '../api/types';
import MoveNameModal from './MoveNameModal';
import ChangeAcceptedModal from './ChangeAcceptedModal';

// How many of the closest ancestors to show before collapsing the rest behind a leading "…".
const MAX_CRUMBS = 4;

export interface ClassificationBarProps {
  pid: number;
  usage: NameUsage;
  canEdit: boolean;
  /** navigate the form to another usage (the parent owns the selected id); links are plain text
   *  without it. */
  onNavigate?: (id: number) => void;
}

// A one-line classification of the focal taxon shown in the edit form: the closest ancestors
// (higher ones collapsed into "…"), each a link, ending at the direct parent which carries a
// change icon opening the move flow (reparent an accepted taxon, or change a synonym's accepted
// name). The path shown is that of the focal taxon's *parent*: parent_id for a tree node, or the
// (primary) accepted name for a synonym/misapplied usage -- so getPath's accepted-only walk always
// applies and the last entry is the direct parent, not the focal taxon itself.
export default function ClassificationBar({ pid, usage, canEdit, onNavigate }: ClassificationBarProps) {
  const isAccepted = usage.status === 'ACCEPTED';
  const isSynonym = usage.status === 'SYNONYM' || usage.status === 'MISAPPLIED';
  const anchorId = isSynonym ? usage.acceptedParentIds?.[0] ?? null : usage.parentId;

  const [moveOpen, setMoveOpen] = useState(false);
  const [changeOpen, setChangeOpen] = useState(false);

  const { data: path, isLoading } = useQuery({
    queryKey: ['treePath', pid, anchorId],
    queryFn: () => getPath(pid, anchorId as number),
    enabled: anchorId != null,
  });

  if (anchorId == null) return null;
  if (isLoading) return <Loader size="xs" />;
  const full = path ?? [];
  if (full.length === 0) return null;

  const truncated = full.length > MAX_CRUMBS;
  const shown = truncated ? full.slice(full.length - MAX_CRUMBS) : full;
  const lastIndex = shown.length - 1;
  // the change icon is available for a reparentable accepted taxon or a re-linkable synonym; an
  // unassessed taxon shows the path but has no (accepted-only) reparent flow.
  const canChange = canEdit && (isAccepted || isSynonym);

  const crumbs: ReactNode[] = [];
  if (truncated) crumbs.push(<Text key="ellipsis" size="sm" c="dimmed">…</Text>);
  shown.forEach((node, i) => {
    if (truncated || i > 0) {
      crumbs.push(
        <Text key={`sep-${node.id}`} size="sm" c="dimmed">
          {'>'}
        </Text>,
      );
    }
    const isLast = i === lastIndex;
    crumbs.push(
      onNavigate ? (
        <Anchor
          key={node.id}
          size="sm"
          fw={isLast ? 600 : 400}
          style={{ cursor: 'pointer' }}
          onClick={() => onNavigate(node.id)}
        >
          {node.scientificName}
        </Anchor>
      ) : (
        <Text key={node.id} size="sm" fw={isLast ? 600 : 400}>
          {node.scientificName}
        </Text>
      ),
    );
  });

  return (
    <Group gap={6} wrap="wrap" align="center" mb="xs">
      {crumbs}
      {canChange && (
        <Tooltip label={isSynonym ? 'Change accepted name' : 'Move to another parent'} withArrow>
          <ActionIcon
            variant="subtle"
            color="gray"
            size="sm"
            aria-label="Change parent"
            onClick={() => (isSynonym ? setChangeOpen(true) : setMoveOpen(true))}
          >
            <IconArrowsExchange size={15} />
          </ActionIcon>
        </Tooltip>
      )}
      {isAccepted && (
        <MoveNameModal
          pid={pid}
          usage={{ id: usage.id, scientificName: usage.scientificName }}
          opened={moveOpen}
          onClose={() => setMoveOpen(false)}
        />
      )}
      {isSynonym && (
        <ChangeAcceptedModal
          pid={pid}
          usage={{ id: usage.id, scientificName: usage.scientificName }}
          currentAcceptedId={anchorId}
          opened={changeOpen}
          onClose={() => setChangeOpen(false)}
        />
      )}
    </Group>
  );
}
