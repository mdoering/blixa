import { ActionIcon, Badge, Box, Group, Loader, Stack, Text } from '@mantine/core';
import { IconChevronDown, IconChevronRight } from '@tabler/icons-react';
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getChildren } from '../api/tree';
import type { TreeNode } from '../api/types';
import NameActionMenu from '../names/NameActionMenu';

const INDENT_PX = 20;

export interface TreeNodeRowProps {
  pid: number;
  node: TreeNode;
  depth: number;
  selectedId: number | null;
  onSelect: (id: number) => void;
  canEdit?: boolean;
  // Called after this row's (or any descendant row's) delete succeeds, with the deleted usage's
  // id -- lets the page clear its selection if the deleted row was the one selected.
  onAfterDelete?: (deletedId: number) => void;
  // See ClassificationTree: when this row's node matches, it (and its subtree) is disabled as a
  // Move target -- non-selectable and non-expandable.
  disabledId?: number;
}

export default function TreeNodeRow({
  pid,
  node,
  depth,
  selectedId,
  onSelect,
  canEdit = false,
  onAfterDelete,
  disabledId,
}: TreeNodeRowProps) {
  const [expanded, setExpanded] = useState(false);
  const [hovered, setHovered] = useState(false);
  const [menuOpened, setMenuOpened] = useState(false);
  const disabled = disabledId === node.id;
  // A disabled node's whole subtree is off-limits as a target, so it can't be expanded to reach it.
  const hasChildren = node.childCount > 0 && !disabled;
  const selected = selectedId === node.id;

  // Lazy: children are only fetched once this node is expanded, and stay cached by
  // TanStack Query afterwards (collapsing/re-expanding doesn't refetch).
  const { data: children, isLoading } = useQuery({
    queryKey: ['treeChildren', pid, node.id],
    queryFn: () => getChildren(pid, node.id),
    enabled: expanded && hasChildren,
  });

  return (
    <Stack gap={0}>
      <Group
        gap={4}
        wrap="nowrap"
        pl={depth * INDENT_PX}
        py={2}
        onMouseEnter={() => setHovered(true)}
        onMouseLeave={() => setHovered(false)}
        onContextMenu={(e) => {
          if (!canEdit) return;
          e.preventDefault();
          setMenuOpened(true);
        }}
      >
        {hasChildren ? (
          <ActionIcon
            variant="subtle"
            color="gray"
            size="sm"
            aria-label={expanded ? 'Collapse' : 'Expand'}
            onClick={() => setExpanded((e) => !e)}
          >
            {expanded ? <IconChevronDown size={14} /> : <IconChevronRight size={14} />}
          </ActionIcon>
        ) : (
          <div style={{ width: 28, flexShrink: 0 }} />
        )}
        <Group
          gap={6}
          wrap="nowrap"
          onClick={() => !disabled && onSelect(node.id)}
          style={{
            cursor: disabled ? 'not-allowed' : 'pointer',
            opacity: disabled ? 0.45 : 1,
            flex: 1,
            borderRadius: 4,
            paddingInline: 4,
            backgroundColor: selected ? 'var(--mantine-color-blue-light)' : undefined,
          }}
        >
          {node.rank && (
            <Badge size="xs" variant="light" color="gray" style={{ flexShrink: 0 }}>
              {node.rank}
            </Badge>
          )}
          <Text size="sm" fw={selected ? 700 : 400} truncate>
            {node.scientificName}
          </Text>
          {node.authorship && (
            <Text c="dimmed" size="xs" truncate>
              {node.authorship}
            </Text>
          )}
        </Group>
        <Box style={{ opacity: hovered || selected || menuOpened ? 1 : 0, flexShrink: 0 }}>
          <NameActionMenu
            pid={pid}
            usage={node}
            canEdit={canEdit}
            onSelect={onSelect}
            opened={menuOpened}
            onChange={setMenuOpened}
            onAfterDelete={onAfterDelete}
          />
        </Box>
      </Group>
      {expanded && (
        <Stack gap={0}>
          {isLoading && (
            <Text size="xs" c="dimmed" pl={(depth + 1) * INDENT_PX + 28}>
              <Loader size="xs" mr={4} style={{ verticalAlign: 'middle' }} />
              Loading…
            </Text>
          )}
          {/* No virtualization yet: a page of children (server default limit) renders in full.
              Follow-up: paginate/virtualize very large sibling lists. */}
          {(children ?? []).map((child) => (
            <TreeNodeRow
              key={child.id}
              pid={pid}
              node={child}
              depth={depth + 1}
              selectedId={selectedId}
              onSelect={onSelect}
              canEdit={canEdit}
              onAfterDelete={onAfterDelete}
              disabledId={disabledId}
            />
          ))}
        </Stack>
      )}
    </Stack>
  );
}
