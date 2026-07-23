import { Anchor, Button, Group, Modal, Stack, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconSearch } from '@tabler/icons-react';
import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { messageFor } from '../api/client';
import { bhlPublicationSearch, setReferenceBhlItem } from '../api/bhl';

interface BhlLinkModalProps {
  pid: number;
  refId: number;
  prefill?: string;
  opened: boolean;
  onClose: () => void;
  onLinked: (itemId: number) => void;
}

// Search BHL for the digitised volume (item) that matches a reference, and link it. Piece 1 of BHL
// integration -- once a reference has an item, names citing it can find the exact page within it.
export default function BhlLinkModal({
  pid,
  refId,
  prefill,
  opened,
  onClose,
  onLinked,
}: BhlLinkModalProps) {
  const queryClient = useQueryClient();
  const [q, setQ] = useState(prefill ?? '');
  const search = useMutation({ mutationFn: (term: string) => bhlPublicationSearch(pid, term) });

  // Prefill the box each time it opens (don't auto-run -- the curator refines, then searches).
  useEffect(() => {
    if (opened) setQ(prefill ?? '');
  }, [opened, prefill]);

  const link = useMutation({
    mutationFn: (itemId: number) => setReferenceBhlItem(pid, refId, itemId),
    onSuccess: (_r, itemId) => {
      notifications.show({ message: 'Linked BHL item' });
      onLinked(itemId);
      queryClient.invalidateQueries({ queryKey: ['references', pid] });
      onClose();
    },
    onError: (e) =>
      notifications.show({ color: 'red', message: messageFor(e, 'Failed to link BHL item') }),
  });

  return (
    <Modal opened={opened} onClose={onClose} size="lg" title="Find on BHL">
      <Stack gap="sm">
        <Group gap="xs" wrap="nowrap">
          <TextInput
            style={{ flex: 1 }}
            placeholder="Title, author or year…"
            leftSection={<IconSearch size={14} />}
            value={q}
            onChange={(e) => setQ(e.currentTarget.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && q.trim()) search.mutate(q.trim());
            }}
          />
          <Button onClick={() => q.trim() && search.mutate(q.trim())} loading={search.isPending}>
            Search
          </Button>
        </Group>

        {search.isError && (
          <Text c="red" size="sm">
            {messageFor(search.error, 'BHL search failed')}
          </Text>
        )}
        {search.data && search.data.length === 0 && (
          <Text c="dimmed" size="sm">
            No BHL items found.
          </Text>
        )}

        <Stack gap="xs">
          {search.data?.map((item, i) => (
            <Group key={item.itemId ?? i} justify="space-between" wrap="nowrap">
              <Stack gap={0} style={{ minWidth: 0 }}>
                <Anchor
                  href={item.url ?? undefined}
                  target="_blank"
                  rel="noreferrer"
                  size="sm"
                  truncate
                >
                  {item.title ?? '(untitled)'}
                </Anchor>
                <Text size="xs" c="dimmed" truncate>
                  {[item.authors, item.year].filter(Boolean).join(' · ')}
                </Text>
              </Stack>
              <Button
                size="xs"
                variant="light"
                disabled={item.itemId == null}
                loading={link.isPending && link.variables === item.itemId}
                onClick={() => item.itemId != null && link.mutate(item.itemId)}
              >
                Link
              </Button>
            </Group>
          ))}
        </Stack>
      </Stack>
    </Modal>
  );
}
