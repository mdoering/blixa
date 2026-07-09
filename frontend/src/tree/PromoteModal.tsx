import { Box, Button, Checkbox, Group, Modal, SegmentedControl, Stack, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, messageFor } from '../api/client';
import { getAccepted, getUsage, promoteUsage } from '../api/usages';
import ClassificationTree from './ClassificationTree';
import type { MoveTarget } from './MoveNameModal';

export interface PromoteModalProps {
  pid: number;
  usage: MoveTarget;
  opened: boolean;
  onClose: () => void;
}

type Mode = 'parent' | 'root';

// syn -> acc: promote a synonym/misapplied usage to an accepted name — pick a parent in the tree, or
// make it a root. Its synonym links are dropped server-side (see NameUsageService.promote). The node
// is a synonym, so it doesn't appear in the accepted-only picker (no self-selection to worry about).
export default function PromoteModal({ pid, usage, opened, onClose }: PromoteModalProps) {
  const queryClient = useQueryClient();
  const [mode, setMode] = useState<Mode>('parent');
  const [parentId, setParentId] = useState<number | null>(null);
  const [keep, setKeep] = useState<number[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (opened) {
      setMode('parent');
      setParentId(null);
      setKeep([]);
      setError(null);
    }
  }, [opened, usage.id]);

  const { data: node } = useQuery({
    queryKey: ['usage', pid, usage.id],
    queryFn: () => getUsage(pid, usage.id),
    enabled: opened,
  });
  // The accepted names this synonym currently points to — offered as "keep" when it's pro parte.
  const { data: accepteds } = useQuery({
    queryKey: ['usageAccepted', pid, usage.id],
    queryFn: () => getAccepted(pid, usage.id),
    enabled: opened,
  });
  const proParte = (accepteds?.length ?? 0) >= 2;

  const mutation = useMutation({
    mutationFn: () => {
      if (!node) throw new Error('not loaded');
      return promoteUsage(pid, usage.id, {
        parentId: mode === 'root' ? null : parentId,
        keepAcceptedIds: keep.length ? keep : undefined,
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
      notifications.show({ message: 'Promoted to accepted' });
      onClose();
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 409) {
        notifications.show({ color: 'orange', message: 'Changed by someone else — refreshing' });
        void queryClient.invalidateQueries({ queryKey: ['usage', pid, usage.id] });
        onClose();
        return;
      }
      setError(messageFor(e, 'Could not promote this name'));
    },
  });

  const canPromote = mode === 'root' || parentId != null;

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={
        <Text fw={600}>
          Promote <Text span fs="italic" inherit>{usage.scientificName ?? '—'}</Text> to accepted
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
              Pick the parent in the classification.
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
                selectedId={parentId}
                onSelect={(id) => {
                  setParentId(id);
                  setError(null);
                }}
              />
            </Box>
          </>
        ) : (
          <Text size="sm" c="dimmed">
            It becomes a root of the classification.
          </Text>
        )}

        {proParte && (
          <Checkbox.Group
            label="This name is a synonym of several accepted names. Keep any as separate synonyms:"
            value={keep.map(String)}
            onChange={(vals) => setKeep(vals.map(Number))}
          >
            <Stack gap={4} mt="xs">
              {(accepteds ?? []).map((a) => (
                <Checkbox key={a.id} value={String(a.id)} label={a.scientificName ?? `#${a.id}`} />
              ))}
            </Stack>
          </Checkbox.Group>
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
          <Button onClick={() => mutation.mutate()} loading={mutation.isPending} disabled={!canPromote}>
            Promote
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
