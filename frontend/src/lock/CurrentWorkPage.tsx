import { Anchor, Button, Group, Stack, Table, Text, Title } from '@mantine/core';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { listLocks, releaseLock } from '../api/locks';
import type { Lock } from '../api/types';

dayjs.extend(relativeTime);

// Project-level "who's editing what right now" dashboard: a live-polled list of active advisory
// locks, with a Release action on rows the current user holds. Locks are name_usage-only this
// phase and don't carry the scientific name, so the entity cell deep-links into the Names page's
// `?usage=` param rather than joining against the name in the backend.
export default function CurrentWorkPage() {
  const { projectId } = useParams();
  const pid = Number(projectId);
  const queryClient = useQueryClient();

  const { data: locks } = useQuery({
    queryKey: ['locks', pid],
    queryFn: () => listLocks(pid),
    refetchInterval: 20_000,
  });

  const releaseMut = useMutation({
    mutationFn: (lockId: number) => releaseLock(pid, lockId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['locks', pid] }),
  });

  const rows = [...(locks ?? [])].sort((a, b) => (a.acquiredAt < b.acquiredAt ? 1 : -1));

  return (
    <Stack>
      <Title order={3} m={0}>
        Current work
      </Title>

      {rows.length === 0 ? (
        <Text c="dimmed" size="sm">
          No one is editing anything right now.
        </Text>
      ) : (
        <Table striped>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Name/entity</Table.Th>
              <Table.Th>Holder</Table.Th>
              <Table.Th>Since</Table.Th>
              <Table.Th>Expires</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {rows.map((lock: Lock) => (
              <Table.Tr key={lock.id}>
                <Table.Td>
                  <Anchor component={Link} to={`/projects/${pid}/names?usage=${lock.entityId}`} size="sm">
                    {lock.entityType} #{lock.entityId}
                  </Anchor>
                </Table.Td>
                <Table.Td>
                  <Text size="sm">
                    {lock.username}
                    {lock.heldByMe ? ' (you)' : ''}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm" c="dimmed" title={lock.acquiredAt}>
                    {dayjs(lock.acquiredAt).fromNow()}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm" c="dimmed" title={lock.expiresAt}>
                    {dayjs(lock.expiresAt).fromNow()}
                  </Text>
                </Table.Td>
                <Table.Td>
                  {lock.heldByMe && (
                    <Group justify="flex-end">
                      <Button
                        size="xs"
                        variant="light"
                        loading={releaseMut.isPending && releaseMut.variables === lock.id}
                        onClick={() => releaseMut.mutate(lock.id)}
                      >
                        Release
                      </Button>
                    </Group>
                  )}
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </Stack>
  );
}
