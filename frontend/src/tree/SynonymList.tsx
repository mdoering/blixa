import { ActionIcon, Group, List, Text } from '@mantine/core';
import { IconUnlink } from '@tabler/icons-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { notifications } from '@mantine/notifications';
import { messageFor } from '../api/client';
import { getAccepted, getSynonyms, unlinkSynonym } from '../api/usages';

export interface SynonymListProps {
  pid: number;
  usageId: number;
  status: string | null;
  // Owner/editor: show the per-row unlink control. Read-only otherwise.
  canEdit?: boolean;
}

// An accepted usage shows its synonyms; a synonym/misapplied usage shows the accepted usage(s) it
// points to. With canEdit, each row can be unlinked (removes the synonym_accepted link only, in the
// correct direction — the current usage is the synonym in the accepted view, the accepted in the
// synonym view).
export default function SynonymList({ pid, usageId, status, canEdit = false }: SynonymListProps) {
  const queryClient = useQueryClient();
  const isAccepted = (status ?? '').toUpperCase() === 'ACCEPTED';
  const queryKey = isAccepted ? ['usageSynonyms', pid, usageId] : ['usageAccepted', pid, usageId];

  const { data, isLoading } = useQuery({
    queryKey,
    queryFn: () => (isAccepted ? getSynonyms(pid, usageId) : getAccepted(pid, usageId)),
  });

  const unlink = useMutation({
    mutationFn: (rowId: number) =>
      isAccepted ? unlinkSynonym(pid, rowId, usageId) : unlinkSynonym(pid, usageId, rowId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey });
      await queryClient.invalidateQueries({ queryKey: ['usage', pid, usageId] });
      await queryClient.invalidateQueries({ queryKey: ['usageSynonyms', pid] });
      await queryClient.invalidateQueries({ queryKey: ['usageAccepted', pid] });
      notifications.show({ message: 'Unlinked' });
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not unlink') }),
  });

  if (isLoading) return <Text size="sm" c="dimmed">Loading…</Text>;

  const items = data ?? [];
  if (items.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        {isAccepted ? 'No synonyms' : 'No accepted name'}
      </Text>
    );
  }

  return (
    <List size="sm" spacing={4}>
      {items.map((u) => (
        <List.Item key={u.id}>
          <Group gap={6} wrap="nowrap">
            <span>
              {u.scientificName}
              {u.authorship ? (
                <Text span c="dimmed" size="xs">
                  {' '}
                  {u.authorship}
                </Text>
              ) : null}
            </span>
            {canEdit && (
              <ActionIcon
                variant="subtle"
                color="gray"
                size="sm"
                aria-label={`Unlink ${u.scientificName}`}
                onClick={() => unlink.mutate(u.id)}
              >
                <IconUnlink size={14} />
              </ActionIcon>
            )}
          </Group>
        </List.Item>
      ))}
    </List>
  );
}
