import { Loader, Stack, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { getRoots } from '../api/tree';
import TreeNodeRow from './TreeNodeRow';

export interface ClassificationTreeProps {
  pid: number;
  selectedId: number | null;
  onSelect: (id: number) => void;
  // Whether the current user may create/edit/delete names (owner|editor); threaded down to each
  // row's action menu, which hides itself entirely when this is false. Defaults to false so
  // existing callers that don't pass it (read-only usage) don't show write actions.
  canEdit?: boolean;
  // Threaded down to every row's action menu; called after a delete succeeds with the deleted
  // usage's id so the caller can clear its selection if that row was selected.
  onAfterDelete?: (deletedId: number) => void;
  // When set (used by the Move target-picker), the row for this usage is shown non-selectable and
  // non-expandable -- so neither the node itself nor any of its descendants (only reachable by
  // expanding it, since the tree is single-parent) can be chosen as a new parent.
  disabledId?: number;
}

// Lazy classification tree: only the root level is fetched eagerly; every other level is
// fetched on demand when its parent row is expanded (see TreeNodeRow). No virtualization
// yet -- a follow-up should add it (or server paging controls) for very large sibling lists.
export default function ClassificationTree({
  pid,
  selectedId,
  onSelect,
  canEdit = false,
  onAfterDelete,
  disabledId,
}: ClassificationTreeProps) {
  const {
    data: roots,
    isLoading,
    isError,
  } = useQuery({
    queryKey: ['treeRoots', pid],
    queryFn: () => getRoots(pid),
  });

  if (isLoading) return <Loader size="sm" />;
  if (isError) return <Text c="red">Could not load the tree</Text>;
  if (!roots || roots.length === 0) return <Text c="dimmed">No taxa yet</Text>;

  return (
    <Stack gap={0}>
      {roots.map((node) => (
        <TreeNodeRow
          key={node.id}
          pid={pid}
          node={node}
          depth={0}
          selectedId={selectedId}
          onSelect={onSelect}
          canEdit={canEdit}
          onAfterDelete={onAfterDelete}
          disabledId={disabledId}
        />
      ))}
    </Stack>
  );
}
