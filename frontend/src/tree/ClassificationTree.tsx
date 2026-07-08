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
}

// Lazy classification tree: only the root level is fetched eagerly; every other level is
// fetched on demand when its parent row is expanded (see TreeNodeRow). No virtualization
// yet -- a follow-up should add it (or server paging controls) for very large sibling lists.
export default function ClassificationTree({
  pid,
  selectedId,
  onSelect,
  canEdit = false,
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
        />
      ))}
    </Stack>
  );
}
