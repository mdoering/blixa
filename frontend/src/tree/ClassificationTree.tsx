import { Group, Loader, Stack, Text, UnstyledButton } from '@mantine/core';
import { IconChevronDown } from '@tabler/icons-react';
import { useInfiniteQuery } from '@tanstack/react-query';
import { getRoots } from '../api/tree';
import TreeNodeRow from './TreeNodeRow';

const ROOTS_PAGE = 50;

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
  // When true, the tree also shows UNASSESSED ("provisionally accepted") nodes (visually marked);
  // default (false) shows only the accepted backbone. Threaded through to every children fetch.
  includeUnassessed?: boolean;
  // Ids of the nodes to expand so the selected usage is visible (its ancestors).
  revealIds?: ReadonlySet<number>;
}

// Lazy classification tree: only the root level is fetched eagerly (paged); every other level is
// fetched on demand when its parent row is expanded (see TreeNodeRow). Roots have no parent to
// carry a total, so paging uses the page-length heuristic (a full page -> maybe more). Row
// virtualization is a separate follow-up.
export default function ClassificationTree({
  pid,
  selectedId,
  onSelect,
  canEdit = false,
  onAfterDelete,
  disabledId,
  includeUnassessed = false,
  revealIds,
}: ClassificationTreeProps) {
  const {
    data: rootPages,
    isLoading,
    isError,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
  } = useInfiniteQuery({
    queryKey: ['treeRoots', pid, includeUnassessed],
    queryFn: ({ pageParam }) =>
      getRoots(pid, { unassessed: includeUnassessed, limit: ROOTS_PAGE, offset: pageParam }),
    initialPageParam: 0,
    // No total for roots: another page may exist only if the last one came back full.
    getNextPageParam: (lastPage, allPages) =>
      lastPage.length === ROOTS_PAGE ? allPages.reduce((n, p) => n + p.length, 0) : undefined,
  });
  const roots = rootPages?.pages.flat() ?? [];

  if (isLoading) return <Loader size="sm" />;
  if (isError) return <Text c="red">Could not load the tree</Text>;
  if (roots.length === 0) return <Text c="dimmed">No taxa yet</Text>;

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
          includeUnassessed={includeUnassessed}
          revealIds={revealIds}
        />
      ))}
      {hasNextPage && (
        <UnstyledButton onClick={() => fetchNextPage()} disabled={isFetchingNextPage} py={4} pl={28}>
          <Group gap={6} wrap="nowrap">
            {isFetchingNextPage ? <Loader size="xs" /> : <IconChevronDown size={14} />}
            <Text size="xs" c="dimmed">
              {isFetchingNextPage ? 'Loading…' : 'Load more'}
            </Text>
          </Group>
        </UnstyledButton>
      )}
    </Stack>
  );
}
