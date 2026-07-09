import { Box, Button, Group, Modal, SegmentedControl, Stack, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, messageFor } from '../api/client';
import { moveParent } from '../api/tree';
import { getUsage } from '../api/usages';
import ClassificationTree from './ClassificationTree';

// The minimal shape needed to move a node -- both TreeNode and NameUsage satisfy it. (version is
// not carried here: it's re-read fresh inside the mutation, so the optimistic-lock check uses the
// latest value, mirroring how change-status re-loads before writing.)
export interface MoveTarget {
  id: number;
  scientificName: string | null;
}

export interface MoveNameModalProps {
  pid: number;
  usage: MoveTarget;
  opened: boolean;
  onClose: () => void;
}

type Mode = 'parent' | 'root';

// Reparents an accepted usage: pick a new parent in a target-picker tree, or make it a root. The
// picker is the ordinary classification tree with the moved node (and its subtree) disabled, so an
// invalid self/descendant target can't be chosen in the first place; the backend stays the source
// of truth (cycle-safe, 400) and any rejection is surfaced inline so the modal stays open to retry.
export default function MoveNameModal({ pid, usage, opened, onClose }: MoveNameModalProps) {
  const queryClient = useQueryClient();
  const [mode, setMode] = useState<Mode>('parent');
  const [targetId, setTargetId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Fresh open (for this or a different node) starts from a clean slate -- no carried-over target
  // pick or error from a previous move.
  useEffect(() => {
    if (opened) {
      setMode('parent');
      setTargetId(null);
      setError(null);
    }
  }, [opened, usage.id]);

  // Names the chosen parent in the confirmation line; shares the ['usage', pid, id] key with
  // TaxonDetail, so selecting a node already open elsewhere costs no extra request.
  const { data: targetUsage } = useQuery({
    queryKey: ['usage', pid, targetId],
    queryFn: () => getUsage(pid, targetId as number),
    enabled: mode === 'parent' && targetId != null,
  });

  const mutation = useMutation({
    mutationFn: async () => {
      const parentId = mode === 'root' ? null : targetId;
      // Re-read to move against the current version (optimistic lock); a concurrent edit -> 409.
      const full = await getUsage(pid, usage.id);
      await moveParent(pid, usage.id, parentId, full.version);
    },
    onSuccess: async () => {
      // Refresh both the old and new parent's child lists + root list (childCounts shift), plus the
      // moved node's own detail and breadcrumb path. Invalidating ['treeChildren', pid] is a prefix
      // match, so every expanded level refetches.
      await queryClient.invalidateQueries({ queryKey: ['treeRoots', pid] });
      await queryClient.invalidateQueries({ queryKey: ['treeChildren', pid] });
      await queryClient.invalidateQueries({ queryKey: ['usageSearch', pid] });
      await queryClient.invalidateQueries({ queryKey: ['usage', pid, usage.id] });
      await queryClient.invalidateQueries({ queryKey: ['treePath', pid] });
      notifications.show({ message: 'Moved' });
      onClose();
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 409) {
        notifications.show({ color: 'orange', message: 'Changed by someone else — refreshing' });
        void queryClient.invalidateQueries({ queryKey: ['usage', pid, usage.id] });
        onClose();
        return;
      }
      // 400 (self/descendant/non-accepted parent, per the cycle-safe backend): keep the modal open
      // and show why, so a different target can be picked.
      setError(messageFor(e, 'Could not move this name'));
    },
  });

  const canMove = mode === 'root' || targetId != null;

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={
        <Text fw={600}>
          Move <Text span fs="italic" inherit>{usage.scientificName ?? '—'}</Text>
        </Text>
      }
    >
      <Stack gap="md">
        <SegmentedControl
          value={mode}
          onChange={(v) => {
            setMode(v as Mode);
            setError(null);
          }}
          data={[
            { label: 'Under a parent', value: 'parent' },
            { label: 'Make it a root', value: 'root' },
          ]}
        />
        {mode === 'parent' ? (
          <>
            <Text size="sm" c="dimmed">
              Pick the new parent below. The name being moved and its descendants are greyed out.
            </Text>
            <Box
              style={{
                maxHeight: 320,
                overflowY: 'auto',
                border: '1px solid var(--mantine-color-gray-3)',
                borderRadius: 4,
                padding: 4,
              }}
            >
              <ClassificationTree
                pid={pid}
                selectedId={targetId}
                onSelect={(id) => {
                  setTargetId(id);
                  setError(null);
                }}
                disabledId={usage.id}
              />
            </Box>
            {targetUsage && (
              <Text size="sm">
                New parent:{' '}
                <Text span fw={600} inherit>
                  {targetUsage.scientificName}
                </Text>
              </Text>
            )}
          </>
        ) : (
          <Text size="sm" c="dimmed">
            Removes its parent, making it a root of the classification.
          </Text>
        )}
        {error && (
          <Text c="red" size="sm">
            {error}
          </Text>
        )}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={() => mutation.mutate()} loading={mutation.isPending} disabled={!canMove}>
            Move
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
