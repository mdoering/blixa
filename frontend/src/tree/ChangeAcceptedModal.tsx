import { Box, Button, Group, Modal, Stack, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { messageFor } from '../api/client';
import { linkSynonym, unlinkSynonym } from '../api/usages';
import ClassificationTree from './ClassificationTree';
import type { MoveTarget } from './MoveNameModal';

export interface ChangeAcceptedModalProps {
  pid: number;
  usage: MoveTarget; // the synonym/misapplied usage being re-pointed
  currentAcceptedId: number; // the accepted link being replaced
  opened: boolean;
  onClose: () => void;
}

// Changes (replaces) the accepted name a synonym/misapplied usage points to: link the newly picked
// accepted, then unlink the current one. Picking the current target is a no-op (Change disabled).
export default function ChangeAcceptedModal({
  pid,
  usage,
  currentAcceptedId,
  opened,
  onClose,
}: ChangeAcceptedModalProps) {
  const queryClient = useQueryClient();
  const [targetId, setTargetId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (opened) {
      setTargetId(null);
      setError(null);
    }
  }, [opened, usage.id]);

  const mutation = useMutation({
    mutationFn: async () => {
      const target = targetId as number;
      // link the new accepted first, then drop the old link -- so the synonym is never briefly
      // orphaned if the second call fails.
      await linkSynonym(pid, usage.id, target);
      await unlinkSynonym(pid, usage.id, currentAcceptedId);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['usage', pid, usage.id] });
      await queryClient.invalidateQueries({ queryKey: ['usageAccepted', pid] });
      await queryClient.invalidateQueries({ queryKey: ['usageSynonyms', pid] });
      await queryClient.invalidateQueries({ queryKey: ['treePath', pid] });
      notifications.show({ message: 'Changed accepted name' });
      onClose();
    },
    onError: (e) => setError(messageFor(e, 'Could not change the accepted name')),
  });

  const canChange = targetId != null && targetId !== currentAcceptedId;

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={
        <Text fw={600}>
          Change accepted name for{' '}
          <Text span fs="italic" inherit>
            {usage.scientificName ?? '—'}
          </Text>
        </Text>
      }
    >
      <Stack gap="md">
        <Text size="sm" c="dimmed">
          Pick the accepted name this synonym should point to instead.
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
          />
        </Box>
        {error && (
          <Text c="red" size="sm">
            {error}
          </Text>
        )}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={() => mutation.mutate()} loading={mutation.isPending} disabled={!canChange}>
            Change
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
