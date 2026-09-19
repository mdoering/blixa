import { ActionIcon, Anchor, Group, Loader, Text, Tooltip } from '@mantine/core';
import { IconArrowsExchange } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import type { ReactNode } from 'react';
import { getPath } from '../api/tree';
import { getUsage } from '../api/usages';
import type { NameUsage } from '../api/types';
import MoveNameModal from './MoveNameModal';

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
// (higher ones collapsed into "…"), each a link. For a tree node (accepted or unassessed) the path
// is that of its *parent*, ending at the direct parent, which carries a change icon opening the
// reparent flow. A synonym/misapplied usage shows the classification of its (primary) accepted
// name instead -- the path of that name's parent, since the accepted name itself (and the icon
// re-pointing the synonym) sits on NameHeader's "Synonym of" line right above.
export default function ClassificationBar({ pid, usage, canEdit, onNavigate }: ClassificationBarProps) {
  const isSynonym = usage.status === 'SYNONYM' || usage.status === 'MISAPPLIED';
  // a tree node (accepted or unassessed "provisionally accepted") -- reparentable, and its path is
  // the parent_id chain.
  const isTaxon = usage.status === 'ACCEPTED' || usage.status === 'UNASSESSED';
  const acceptedId = isSynonym ? usage.acceptedParentIds?.[0] ?? null : null;

  const [moveOpen, setMoveOpen] = useState(false);

  // Same ['usage', pid, id] key as NameHeader/TaxonDetail, so this is served from their cache.
  const { data: accepted } = useQuery({
    queryKey: ['usage', pid, acceptedId],
    queryFn: () => getUsage(pid, acceptedId as number),
    enabled: acceptedId != null,
  });
  const anchorId = isSynonym ? accepted?.parentId ?? null : usage.parentId;

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
  const canChange = canEdit && isTaxon;

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
        <Tooltip label="Move to another parent" withArrow>
          <ActionIcon
            variant="subtle"
            color="gray"
            size="sm"
            aria-label="Change parent"
            onClick={() => setMoveOpen(true)}
          >
            <IconArrowsExchange size={15} />
          </ActionIcon>
        </Tooltip>
      )}
      {isTaxon && (
        <MoveNameModal
          pid={pid}
          usage={{ id: usage.id, scientificName: usage.scientificName }}
          opened={moveOpen}
          onClose={() => setMoveOpen(false)}
        />
      )}
    </Group>
  );
}
