import { Box, Button, Group, Modal, Stack, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { messageFor } from '../api/client';
import { linkSynonym } from '../api/usages';
import ClassificationTree from './ClassificationTree';
import type { MoveTarget } from './MoveNameModal';

export interface LinkAcceptedModalProps {
  pid: number;
  usage: MoveTarget; // the synonym/misapplied usage gaining another accepted target
  opened: boolean;
  onClose: () => void;
}

// Links a synonym/misapplied usage to an ADDITIONAL accepted name, making it pro parte. Pick the
// accepted name in the tree; linking an already-linked pair is a server-side no-op.
export default function LinkAcceptedModal({ pid, usage, opened, onClose }: LinkAcceptedModalProps) {
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
    mutationFn: () => linkSynonym(pid, usage.id, targetId as number),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['usage', pid, usage.id] });
      await queryClient.invalidateQueries({ queryKey: ['usageAccepted', pid] });
      await queryClient.invalidateQueries({ queryKey: ['usageSynonyms', pid] });
      notifications.show({ message: 'Linked to accepted name' });
      onClose();
    },
    onError: (e) => setError(messageFor(e, 'Could not link')),
  });

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={
        <Text fw={600}>
          Add an accepted name for{' '}
          <Text span fs="italic" inherit>{usage.scientificName ?? '—'}</Text>
        </Text>
      }
    >
      <Stack gap="md">
        <Text size="sm" c="dimmed">
          Pick the accepted name this becomes a (pro parte) synonym of.
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
          <Button onClick={() => mutation.mutate()} loading={mutation.isPending} disabled={targetId == null}>
            Link
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
