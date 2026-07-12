import { Anchor, Button, Group, Paper, Select, Stack, Text, TextInput, Title } from '@mantine/core';
import { useForm } from '@mantine/form';
import { modals } from '@mantine/modals';
import { notifications } from '@mantine/notifications';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { MantineReactTable, useMantineReactTable, type MRT_ColumnDef } from 'mantine-react-table';
import { useMemo } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { getProject, listMembers, removeMember, setMember } from '../api/projects';
import { dismissJoinRequest, listJoinRequests } from '../api/join';
import { messageFor } from '../api/client';
import type { Member, Role } from '../api/types';

dayjs.extend(relativeTime);

const ROLES: Role[] = ['owner', 'editor', 'viewer'];
const ROLE_DATA = ROLES.map((r) => ({ value: r, label: r }));

export default function MembersPage() {
  const { projectId } = useParams();
  const id = Number(projectId);
  const queryClient = useQueryClient();
  const form = useForm<{ username: string; role: Role }>({
    initialValues: { username: '', role: 'editor' },
    validate: {
      username: (v) => (v ? null : 'Required'),
    },
  });

  const { data: project } = useQuery({ queryKey: ['project', id], queryFn: () => getProject(id) });
  const { data: members, isLoading } = useQuery({ queryKey: ['members', id], queryFn: () => listMembers(id) });
  const canManage = project?.role === 'owner';

  const { data: joinRequests } = useQuery({
    queryKey: ['joinRequests', id],
    queryFn: () => listJoinRequests(id),
    enabled: canManage,
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['members', id] });

  const setMut = useMutation({
    mutationFn: ({ username, role }: { username: string; role: Role }) => setMember(id, username, role),
    onSuccess: async () => {
      await invalidate();
      form.reset();
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not update member') }),
  });
  const removeMut = useMutation({
    mutationFn: (userId: number) => removeMember(id, userId),
    onSuccess: invalidate,
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not remove member') }),
  });
  const dismissMut = useMutation({
    mutationFn: (requestId: number) => dismissJoinRequest(id, requestId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['joinRequests', id] });
      queryClient.invalidateQueries({ queryKey: ['joinRequestCount', id] });
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not dismiss request') }),
  });

  const columns = useMemo<MRT_ColumnDef<Member>[]>(
    () => [
      { accessorKey: 'username', header: 'Username' },
      {
        accessorKey: 'role',
        header: 'Role',
        Cell: ({ row }) =>
          canManage ? (
            <Select
              data={ROLE_DATA}
              value={row.original.role}
              onChange={(r) => r && setMut.mutate({ username: row.original.username, role: r as Role })}
            />
          ) : (
            row.original.role
          ),
      },
    ],
    [canManage, setMut],
  );

  const table = useMantineReactTable({
    columns,
    data: members ?? [],
    state: { isLoading },
    getRowId: (row) => String(row.userId),
    enablePagination: false,
    enableColumnActions: false,
    enableColumnFilters: false,
    enableSorting: false,
    enableTopToolbar: false,
    enableBottomToolbar: false,
    enableRowActions: canManage,
    positionActionsColumn: 'last',
    renderRowActions: ({ row }) =>
      canManage ? (
        <Button
          color="red"
          size="xs"
          onClick={() =>
            modals.openConfirmModal({
              title: 'Remove member?',
              labels: { confirm: 'Remove', cancel: 'Cancel' },
              onConfirm: () => removeMut.mutate(row.original.userId),
            })
          }
        >
          Remove
        </Button>
      ) : null,
  });

  return (
    <div>
      {canManage && (
        <form onSubmit={form.onSubmit((v) => setMut.mutate(v))}>
          <Group align="flex-end" mb="md">
            <TextInput placeholder="username" {...form.getInputProps('username')} />
            <Select data={ROLE_DATA} w={140} {...form.getInputProps('role')} />
            <Button type="submit" loading={setMut.isPending}>
              Add / update
            </Button>
          </Group>
        </form>
      )}
      <MantineReactTable table={table} />

      {canManage && joinRequests && joinRequests.length > 0 && (
        <Stack gap="xs" mt="xl">
          <Title order={4}>Join requests</Title>
          <Text size="sm" c="dimmed">
            Add them as a member once they've signed in with this ORCID.
          </Text>
          {joinRequests.map((r) => (
            <Paper key={r.id} withBorder p="sm">
              <Group justify="space-between" align="flex-start" wrap="nowrap">
                <Stack gap={2}>
                  <Group gap="xs">
                    <Anchor href={`https://orcid.org/${r.orcid}`} target="_blank" rel="noreferrer" size="sm">
                      {r.orcid}
                    </Anchor>
                    {r.name && <Text size="sm">{r.name}</Text>}
                  </Group>
                  {r.message && (
                    <Text size="sm" c="dimmed">
                      {r.message}
                    </Text>
                  )}
                  <Text size="xs" c="dimmed">
                    {dayjs(r.createdAt).fromNow()}
                  </Text>
                </Stack>
                <Button
                  size="xs"
                  variant="subtle"
                  color="red"
                  loading={dismissMut.isPending && dismissMut.variables === r.id}
                  onClick={() => dismissMut.mutate(r.id)}
                >
                  Dismiss
                </Button>
              </Group>
            </Paper>
          ))}
        </Stack>
      )}
    </div>
  );
}
