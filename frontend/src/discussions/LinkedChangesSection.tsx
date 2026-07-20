import { ActionIcon, Anchor, Badge, Button, Group, Select, Stack, Text, Title } from '@mantine/core';
import { IconX } from '@tabler/icons-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { notifications } from '@mantine/notifications';
import { messageFor } from '../api/client';
import { listChanges } from '../api/changes';
import {
  linkDiscussionChange,
  listDiscussionChanges,
  unlinkDiscussionChange,
} from '../api/discussions';
import type { Change } from '../api/types';

function optionLabel(c: Change): string {
  return `${c.operation} ${c.entityType} #${c.entityId} · ${c.username ?? 'unknown'} · ${new Date(
    c.at,
  ).toLocaleString()}`;
}

// The changelog entries linked to a discussion (the changes that resolved / relate to it). Any
// member sees the list; an editor can link a recent change or unlink one.
export default function LinkedChangesSection({
  pid,
  did,
  canEdit,
}: {
  pid: number;
  did: number;
  canEdit: boolean;
}) {
  const qc = useQueryClient();
  const { data: linked } = useQuery({
    queryKey: ['discussionChanges', pid, did],
    queryFn: () => listDiscussionChanges(pid, did),
  });
  const { data: recent } = useQuery({
    queryKey: ['changes', pid, { limit: 25, offset: 0 }],
    queryFn: () => listChanges(pid, { limit: 25, offset: 0 }),
    enabled: canEdit,
  });
  const [pick, setPick] = useState<string | null>(null);

  const invalidate = () => qc.invalidateQueries({ queryKey: ['discussionChanges', pid, did] });
  const link = useMutation({
    mutationFn: (changeId: number) => linkDiscussionChange(pid, did, changeId),
    onSuccess: async () => {
      await invalidate();
      setPick(null);
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not link') }),
  });
  const unlink = useMutation({
    mutationFn: (changeId: number) => unlinkDiscussionChange(pid, did, changeId),
    onSuccess: invalidate,
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not unlink') }),
  });

  const rows = linked ?? [];
  const linkedIds = new Set(rows.map((c) => c.id));
  const options = (recent ?? [])
    .filter((c) => !linkedIds.has(c.id))
    .map((c) => ({ value: String(c.id), label: optionLabel(c) }));

  return (
    <div>
      <Title order={5} mb="sm">
        Linked changes
      </Title>
      <Stack gap="xs">
        {rows.map((c) => (
          <Group key={c.id} gap="xs" wrap="nowrap">
            <Badge variant="light" size="sm">
              {c.operation}
            </Badge>
            {c.entityType === 'name_usage' ? (
              <Anchor component={Link} to={`/projects/${pid}/names?usage=${c.entityId}`} size="sm">
                {c.entityType} #{c.entityId}
              </Anchor>
            ) : (
              <Text size="sm">
                {c.entityType} #{c.entityId}
              </Text>
            )}
            <Text size="xs" c="dimmed">
              {c.username ?? 'unknown'} · {new Date(c.at).toLocaleDateString()}
            </Text>
            {canEdit && (
              <ActionIcon
                variant="subtle"
                color="gray"
                size="sm"
                aria-label="Unlink change"
                onClick={() => unlink.mutate(c.id)}
              >
                <IconX size={14} />
              </ActionIcon>
            )}
          </Group>
        ))}
        {rows.length === 0 && (
          <Text c="dimmed" size="sm">
            No linked changes.
          </Text>
        )}
      </Stack>
      {canEdit && (
        <Group mt="sm" gap="xs" align="flex-end">
          <Select
            placeholder="Link a recent change…"
            data={options}
            value={pick}
            onChange={setPick}
            searchable
            clearable
            maxDropdownHeight={260}
            style={{ flex: 1 }}
          />
          <Button size="xs" disabled={!pick} onClick={() => pick && link.mutate(Number(pick))} loading={link.isPending}>
            Link
          </Button>
        </Group>
      )}
    </div>
  );
}
