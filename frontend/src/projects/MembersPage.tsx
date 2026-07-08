import { Button, Group, Select, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { modals } from '@mantine/modals';
import { notifications } from '@mantine/notifications';
import { MantineReactTable, useMantineReactTable, type MRT_ColumnDef } from 'mantine-react-table';
import { useMemo } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { getProject, listMembers, removeMember, setMember } from '../api/projects';
import { messageFor } from '../api/client';
import type { Member, Role } from '../api/types';

const ROLES: Role[] = ['owner', 'editor', 'reviewer', 'viewer'];
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
    </div>
  );
}
