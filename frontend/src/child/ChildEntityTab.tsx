import {
  ActionIcon,
  Button,
  Grid,
  Group,
  Menu,
  Modal,
  NumberInput,
  Select,
  Stack,
  Table,
  Text,
  Textarea,
  TextInput,
} from '@mantine/core';
import { modals } from '@mantine/modals';
import { notifications } from '@mantine/notifications';
import { IconDots, IconPlus } from '@tabler/icons-react';
import { useEffect, useState, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, messageFor } from '../api/client';
import EntitySelect, { type Option } from './EntitySelect';

export type FieldType = 'text' | 'textarea' | 'number' | 'select' | 'boolean' | 'entity';

export interface FieldDef<T = unknown> {
  name: string;
  label: string;
  type?: FieldType;
  options?: Option[]; // select
  load?: () => Promise<Option[]>; // entity
  entityQueryKey?: unknown[]; // entity
  current?: (row: T) => Option | null; // entity: label for the selected value when editing
  span?: number; // 1..12, default 6
}

export interface ColumnDef<T> {
  header: string;
  cell: (row: T) => ReactNode;
}

export interface ChildApi<T> {
  list: (pid: number, usageId: number) => Promise<T[]>;
  create: (pid: number, usageId: number, payload: Record<string, unknown>) => Promise<T>;
  update: (pid: number, usageId: number, id: number, payload: Record<string, unknown>) => Promise<T>;
  remove: (pid: number, usageId: number, id: number) => Promise<void>;
}

export interface ChildEntityTabProps<T> {
  pid: number;
  usageId: number;
  canEdit: boolean;
  entity: string; // singular label + query-key base, e.g. 'nameRelation'
  api: ChildApi<T>;
  columns: ColumnDef<T>[];
  fields: FieldDef<T>[];
  rowId: (row: T) => number;
  rowVersion: (row: T) => number;
  toForm: (row: T) => Record<string, string>;
  describe?: (row: T) => string; // for the delete confirm
}

const BOOL_OPTIONS: Option[] = [
  { value: 'true', label: 'Yes' },
  { value: 'false', label: 'No' },
];

/* eslint-disable @typescript-eslint/no-explicit-any */
function emptyValues(fields: FieldDef<any>[]): Record<string, string> {
  return Object.fromEntries(fields.map((f) => [f.name, '']));
}

function buildPayload(
  fields: FieldDef<any>[],
  values: Record<string, string>,
  version?: number,
): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const f of fields) {
    const raw = values[f.name] ?? '';
    if (f.type === 'number' || f.type === 'entity') {
      out[f.name] = raw ? Number(raw) : undefined;
    } else if (f.type === 'boolean') {
      out[f.name] = raw === 'true' ? true : raw === 'false' ? false : undefined;
    } else {
      out[f.name] = raw.trim() ? raw.trim() : undefined;
    }
  }
  if (version != null) out.version = version;
  return out;
}

// Generic list + field-driven add/edit/delete for a name/taxon child-entity (see the child-entities
// spec). Each entity supplies its columns, form fields, and CRUD api; this handles the rest.
export default function ChildEntityTab<T>({
  pid,
  usageId,
  canEdit,
  entity,
  api,
  columns,
  fields,
  rowId,
  rowVersion,
  toForm,
  describe,
}: ChildEntityTabProps<T>) {
  const queryClient = useQueryClient();
  const queryKey = [entity, pid, usageId];
  const [editing, setEditing] = useState<T | null | 'new'>(null); // 'new' = create; T = edit; null = closed
  const [values, setValues] = useState<Record<string, string>>(emptyValues(fields));
  const [error, setError] = useState<string | null>(null);

  const { data } = useQuery({ queryKey, queryFn: () => api.list(pid, usageId) });
  const rows = data ?? [];

  useEffect(() => {
    if (editing === null) return;
    setValues(editing === 'new' ? emptyValues(fields) : toForm(editing));
    setError(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [editing]);

  const invalidate = async () => {
    await queryClient.invalidateQueries({ queryKey });
    await queryClient.invalidateQueries({ queryKey: ['usage', pid, usageId] });
    await queryClient.invalidateQueries({ queryKey: ['changes', pid] });
  };

  const save = useMutation({
    mutationFn: () => {
      if (editing === 'new') return api.create(pid, usageId, buildPayload(fields, values));
      const row = editing as T;
      return api.update(pid, usageId, rowId(row), buildPayload(fields, values, rowVersion(row)));
    },
    onSuccess: async () => {
      await invalidate();
      notifications.show({ message: 'Saved' });
      setEditing(null);
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 409) {
        setError('Changed by someone else — reopen to retry');
        return;
      }
      setError(messageFor(e, 'Could not save'));
    },
  });

  const remove = useMutation({
    mutationFn: (id: number) => api.remove(pid, usageId, id),
    onSuccess: async () => {
      await invalidate();
      notifications.show({ message: 'Deleted' });
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not delete') }),
  });

  const confirmDelete = (row: T) =>
    modals.openConfirmModal({
      title: `Delete this ${entity}?`,
      children: describe ? describe(row) : `This permanently deletes it.`,
      labels: { confirm: 'Delete', cancel: 'Cancel' },
      confirmProps: { color: 'red' },
      onConfirm: () => remove.mutate(rowId(row)),
    });

  return (
    <Stack gap="sm">
      {canEdit && (
        <Group justify="flex-end">
          <Button size="xs" leftSection={<IconPlus size={14} />} onClick={() => setEditing('new')}>
            Add
          </Button>
        </Group>
      )}

      {rows.length === 0 ? (
        <Text size="sm" c="dimmed">
          None
        </Text>
      ) : (
        <Table>
          <Table.Thead>
            <Table.Tr>
              {columns.map((c) => (
                <Table.Th key={c.header}>{c.header}</Table.Th>
              ))}
              {canEdit && <Table.Th />}
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {rows.map((row) => (
              <Table.Tr key={rowId(row)}>
                {columns.map((c) => (
                  <Table.Td key={c.header}>{c.cell(row)}</Table.Td>
                ))}
                {canEdit && (
                  <Table.Td>
                    <Menu withinPortal position="bottom-end">
                      <Menu.Target>
                        <ActionIcon variant="subtle" color="gray" aria-label="Actions">
                          <IconDots size={16} />
                        </ActionIcon>
                      </Menu.Target>
                      <Menu.Dropdown>
                        <Menu.Item onClick={() => setEditing(row)}>Edit</Menu.Item>
                        <Menu.Item color="red" onClick={() => confirmDelete(row)}>
                          Delete
                        </Menu.Item>
                      </Menu.Dropdown>
                    </Menu>
                  </Table.Td>
                )}
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}

      <Modal
        opened={editing !== null}
        onClose={() => setEditing(null)}
        title={editing === 'new' ? `Add ${entity}` : `Edit ${entity}`}
        size="lg"
      >
        <Stack gap="sm">
          <Grid>
            {fields.map((f) => {
              const set = (v: string) => setValues((prev) => ({ ...prev, [f.name]: v }));
              const val = values[f.name] ?? '';
              const editingRow = editing && editing !== 'new' ? (editing as T) : null;
              return (
                <Grid.Col key={f.name} span={f.span ?? 6}>
                  {f.type === 'textarea' ? (
                    <Textarea label={f.label} rows={2} value={val} onChange={(e) => set(e.currentTarget.value)} />
                  ) : f.type === 'number' ? (
                    <NumberInput
                      label={f.label}
                      value={val === '' ? '' : Number(val)}
                      onChange={(v) => set(v === '' || v == null ? '' : String(v))}
                    />
                  ) : f.type === 'select' || f.type === 'boolean' ? (
                    <Select
                      label={f.label}
                      data={f.type === 'boolean' ? BOOL_OPTIONS : f.options ?? []}
                      value={val || null}
                      onChange={(v) => set(v ?? '')}
                      clearable
                      searchable
                    />
                  ) : f.type === 'entity' ? (
                    <EntitySelect
                      label={f.label}
                      value={val || null}
                      onChange={(v) => set(v ?? '')}
                      load={f.load!}
                      queryKey={f.entityQueryKey ?? [f.name]}
                      current={editingRow && f.current ? f.current(editingRow) : null}
                    />
                  ) : (
                    <TextInput label={f.label} value={val} onChange={(e) => set(e.currentTarget.value)} />
                  )}
                </Grid.Col>
              );
            })}
          </Grid>
          {error && (
            <Text c="red" size="sm">
              {error}
            </Text>
          )}
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setEditing(null)}>
              Cancel
            </Button>
            <Button onClick={() => save.mutate()} loading={save.isPending}>
              Save
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}
