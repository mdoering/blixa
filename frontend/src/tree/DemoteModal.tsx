import { Box, Button, Group, Modal, Radio, SegmentedControl, Stack, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, messageFor } from '../api/client';
import { getChildren } from '../api/tree';
import { demoteUsage, getUsage } from '../api/usages';
import ClassificationTree from './ClassificationTree';
import type { MoveTarget } from './MoveNameModal';

export interface DemoteModalProps {
  pid: number;
  usage: MoveTarget;
  // Which non-accepted status the user picked in the ⋮ menu (SYNONYM | MISAPPLIED) — the modal's
  // initial toggle; they can switch it.
  initialStatus: string;
  opened: boolean;
  onClose: () => void;
}

// acc -> syn: pick the accepted name this becomes a synonym of, choose Synonym vs Misapplied, and —
// only when they exist — decide where the node's accepted children go and what happens to its own
// synonyms. Mirrors MoveNameModal's picker (self+subtree greyed) + invalidations. The backend does
// the whole reshuffle atomically (see NameUsageService.demote).
export default function DemoteModal({ pid, usage, initialStatus, opened, onClose }: DemoteModalProps) {
  const queryClient = useQueryClient();
  const [targetId, setTargetId] = useState<number | null>(null);
  const [status, setStatus] = useState('SYNONYM');
  const [childrenTo, setChildrenTo] = useState('new-accepted');
  const [synonymsTo, setSynonymsTo] = useState('new-accepted');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (opened) {
      setTargetId(null);
      setStatus(initialStatus === 'MISAPPLIED' ? 'MISAPPLIED' : 'SYNONYM');
      setChildrenTo('new-accepted');
      setSynonymsTo('new-accepted');
      setError(null);
    }
  }, [opened, usage.id, initialStatus]);

  // Node detail (version + how many synonyms it has) and its accepted children (how many move).
  const { data: node } = useQuery({
    queryKey: ['usage', pid, usage.id],
    queryFn: () => getUsage(pid, usage.id),
    enabled: opened,
  });
  const { data: children } = useQuery({
    queryKey: ['treeChildren', pid, usage.id],
    queryFn: () => getChildren(pid, usage.id),
    enabled: opened,
  });
  const childCount = children?.length ?? 0;
  const synCount = node?.synonymIds?.length ?? 0;

  const mutation = useMutation({
    mutationFn: () => {
      if (!node) throw new Error('not loaded');
      return demoteUsage(pid, usage.id, {
        acceptedId: targetId as number,
        status,
        childrenTo: childCount > 0 ? childrenTo : undefined,
        synonymsTo: synCount > 0 ? synonymsTo : undefined,
        version: node.version,
      });
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['treeRoots', pid] });
      await queryClient.invalidateQueries({ queryKey: ['treeChildren', pid] });
      await queryClient.invalidateQueries({ queryKey: ['usageSearch', pid] });
      await queryClient.invalidateQueries({ queryKey: ['usage', pid] });
      await queryClient.invalidateQueries({ queryKey: ['treePath', pid] });
      await queryClient.invalidateQueries({ queryKey: ['usageSynonyms', pid] });
      await queryClient.invalidateQueries({ queryKey: ['usageAccepted', pid] });
      notifications.show({ message: 'Demoted to synonym' });
      onClose();
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 409) {
        notifications.show({ color: 'orange', message: 'Changed by someone else — refreshing' });
        void queryClient.invalidateQueries({ queryKey: ['usage', pid, usage.id] });
        onClose();
        return;
      }
      setError(messageFor(e, 'Could not demote this name'));
    },
  });

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={
        <Text fw={600}>
          Demote <Text span fs="italic" inherit>{usage.scientificName ?? '—'}</Text> to a synonym
        </Text>
      }
    >
      <Stack gap="md">
        <SegmentedControl
          value={status}
          onChange={setStatus}
          data={[
            { label: 'Synonym', value: 'SYNONYM' },
            { label: 'Misapplied', value: 'MISAPPLIED' },
          ]}
        />
        <Text size="sm" c="dimmed">
          Pick the accepted name it becomes a synonym of. The name being demoted and its descendants
          are greyed out.
        </Text>
        <Box
          style={{
            maxHeight: 280,
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

        {childCount > 0 && (
          <Radio.Group
            label={`Its ${childCount} accepted child${childCount === 1 ? '' : 'ren'} — move to:`}
            value={childrenTo}
            onChange={setChildrenTo}
          >
            <Group mt="xs">
              <Radio value="new-accepted" label="The new accepted name" />
              <Radio value="former-parent" label="Its former parent" />
            </Group>
          </Radio.Group>
        )}

        {synCount > 0 && (
          <Radio.Group
            label={`Its ${synCount} synonym${synCount === 1 ? '' : 's'} — `}
            value={synonymsTo}
            onChange={setSynonymsTo}
          >
            <Group mt="xs">
              <Radio value="new-accepted" label="Re-point to the new accepted" />
              <Radio value="unassessed" label="Set unassessed" />
            </Group>
          </Radio.Group>
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
          <Button
            onClick={() => mutation.mutate()}
            loading={mutation.isPending}
            disabled={targetId == null}
          >
            Demote
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
