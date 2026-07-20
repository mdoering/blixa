import { Badge, Button, Center, Group, Loader, Stack, Switch, Table, Text, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { listUsers, setUserAdmin, setUserState } from '../api/admin';
import { useMe } from '../auth/useMe';

const STATE_COLOR: Record<string, string> = {
  ACTIVE: 'green',
  PENDING: 'yellow',
  DISABLED: 'gray',
};

// Global-admin user administration: approve pending ORCID self-signups, disable/reactivate
// accounts, and grant/revoke the admin flag. The backend (AdminUserController) is the real
// authority -- it 403s non-admins and 400s self-lockout attempts; this page hides the self
// destructive controls and surfaces any backend error as a notification.
export default function AdminUsersPage() {
  const qc = useQueryClient();
  const { data: me } = useMe();
  const { data: users, isLoading } = useQuery({ queryKey: ['adminUsers'], queryFn: listUsers });

  const onError = (e: Error) => notifications.show({ color: 'red', message: e.message });
  const invalidate = () => qc.invalidateQueries({ queryKey: ['adminUsers'] });

  const stateMut = useMutation({
    mutationFn: ({ id, state }: { id: number; state: string }) => setUserState(id, state),
    onSuccess: invalidate,
    onError,
  });
  const adminMut = useMutation({
    mutationFn: ({ id, admin }: { id: number; admin: boolean }) => setUserAdmin(id, admin),
    onSuccess: invalidate,
    onError,
  });

  if (isLoading)
    return (
      <Center style={{ margin: 48 }}>
        <Loader />
      </Center>
    );

  return (
    <Stack>
      <Title order={3}>Users</Title>
      <Table striped highlightOnHover>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>User</Table.Th>
            <Table.Th>ORCID</Table.Th>
            <Table.Th>Status</Table.Th>
            <Table.Th>Admin</Table.Th>
            <Table.Th />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {(users ?? []).map((u) => {
            const self = me?.id === u.id;
            return (
              <Table.Tr key={u.id}>
                <Table.Td>
                  <Text fw={500}>{u.displayName || u.username}</Text>
                  {u.displayName && (
                    <Text size="xs" c="dimmed">
                      {u.username}
                    </Text>
                  )}
                </Table.Td>
                <Table.Td>{u.orcid ?? '—'}</Table.Td>
                <Table.Td>
                  <Badge color={STATE_COLOR[u.state] ?? 'gray'} variant="light">
                    {u.state}
                  </Badge>
                </Table.Td>
                <Table.Td>
                  <Switch
                    checked={u.admin}
                    disabled={self || adminMut.isPending}
                    aria-label={`admin-${u.username}`}
                    onChange={(e) => adminMut.mutate({ id: u.id, admin: e.currentTarget.checked })}
                  />
                </Table.Td>
                <Table.Td>
                  <Group gap="xs" justify="flex-end">
                    {u.state === 'PENDING' && (
                      <Button size="xs" onClick={() => stateMut.mutate({ id: u.id, state: 'ACTIVE' })}>
                        Approve
                      </Button>
                    )}
                    {u.state === 'DISABLED' && (
                      <Button size="xs" variant="light" onClick={() => stateMut.mutate({ id: u.id, state: 'ACTIVE' })}>
                        Reactivate
                      </Button>
                    )}
                    {u.state !== 'DISABLED' && !self && (
                      <Button
                        size="xs"
                        variant="light"
                        color="red"
                        onClick={() => stateMut.mutate({ id: u.id, state: 'DISABLED' })}
                      >
                        Disable
                      </Button>
                    )}
                  </Group>
                </Table.Td>
              </Table.Tr>
            );
          })}
        </Table.Tbody>
      </Table>
    </Stack>
  );
}
