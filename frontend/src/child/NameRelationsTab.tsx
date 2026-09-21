import { Anchor, Stack, Table, Text, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import {
  createNameRelation,
  deleteNameRelation,
  listNameRelations,
  listReverseNameRelations,
  updateNameRelation,
  type NameRelation,
} from '../api/nameRelations';
import { listReferences } from '../api/references';
import { searchUsages } from '../api/usages';
import ChildEntityTab, { type ColumnDef, type FieldDef } from './ChildEntityTab';
import type { Option } from './EntitySelect';

// ColDP NomRelType (common values); TEXT on the wire.
const NOM_REL_TYPES = [
  'basionym',
  'homotypic',
  'spelling correction',
  'based on',
  'replacement name',
  'conserved',
  'later homonym',
  'superfluous',
  'homonym',
  'type',
].map((v) => ({ value: v, label: v }));

export function usageOptions(pid: number, excludeId?: number): () => Promise<Option[]> {
  return () =>
    searchUsages(pid, { limit: 200, offset: 0 }).then((page) =>
      page.items
        .filter((u) => u.id !== excludeId)
        .map((u) => ({ value: String(u.id), label: u.scientificName ?? `#${u.id}` })),
    );
}

export function referenceOptions(pid: number): () => Promise<Option[]> {
  return () =>
    listReferences(pid, { limit: 200, offset: 0 }).then((refs) =>
      refs.map((r) => ({ value: String(r.id), label: r.title ?? r.citation ?? `#${r.id}` })),
    );
}

// A usage's name, as a link that opens it in the detail panel when the host supports navigation.
function UsageLink({
  id,
  name,
  onNavigate,
}: {
  id: number | null;
  name: string | null;
  onNavigate?: (id: number) => void;
}): ReactNode {
  if (id == null) return '—';
  const label = name ?? `#${id}`;
  return onNavigate ? (
    <Anchor size="sm" style={{ cursor: 'pointer' }} onClick={() => onNavigate(id)}>
      {label}
    </Anchor>
  ) : (
    label
  );
}

export default function NameRelationsTab({
  pid,
  usageId,
  canEdit,
  onNavigate,
}: {
  pid: number;
  usageId: number;
  canEdit: boolean;
  onNavigate?: (id: number) => void;
}) {
  // Relations held by OTHER names that point at this one -- read-only here, edited on their owner.
  const { data: reverse } = useQuery({
    queryKey: ['name relation reverse', pid, usageId],
    queryFn: () => listReverseNameRelations(pid, usageId),
  });

  const columns: ColumnDef<NameRelation>[] = [
    { header: 'Type', cell: (r) => r.type ?? '—' },
    {
      header: 'Related name',
      cell: (r) => <UsageLink id={r.relatedUsageId} name={r.relatedName} onNavigate={onNavigate} />,
    },
    { header: 'Page', cell: (r) => r.page ?? '—' },
  ];

  const fields: FieldDef<NameRelation>[] = [
    { name: 'type', label: 'Type', type: 'select', options: NOM_REL_TYPES, span: 6 },
    {
      name: 'relatedUsageId',
      label: 'Related name',
      type: 'entity',
      load: usageOptions(pid, usageId),
      entityQueryKey: ['usageOptions', pid],
      current: (r) =>
        r.relatedUsageId
          ? { value: String(r.relatedUsageId), label: r.relatedName ?? `#${r.relatedUsageId}` }
          : null,
      span: 6,
    },
    {
      name: 'referenceId',
      label: 'Reference',
      type: 'entity',
      load: referenceOptions(pid),
      entityQueryKey: ['refOptions', pid],
      span: 8,
    },
    { name: 'page', label: 'Page', span: 4 },
    { name: 'remarks', label: 'Remarks', type: 'textarea', span: 12 },
  ];

  return (
    <Stack gap="lg">
      <ChildEntityTab<NameRelation>
        pid={pid}
        usageId={usageId}
        canEdit={canEdit}
        entity="name relation"
        api={{
          list: listNameRelations,
          create: createNameRelation,
          update: updateNameRelation,
          remove: deleteNameRelation,
        }}
        columns={columns}
        fields={fields}
        rowId={(r) => r.id}
        rowVersion={(r) => r.version}
        toForm={(r) => ({
          type: r.type ?? '',
          relatedUsageId: r.relatedUsageId ? String(r.relatedUsageId) : '',
          referenceId: r.referenceId ? String(r.referenceId) : '',
          page: r.page ?? '',
          remarks: r.remarks ?? '',
        })}
        describe={(r) => `Delete the ${r.type ?? ''} relation to ${r.relatedName ?? 'this name'}.`}
      />
      {reverse && reverse.length > 0 && (
        <Stack gap="xs">
          <Title order={6}>Relations from other names</Title>
          <Text size="xs" c="dimmed">
            Other names that hold a relation pointing at this one. Edit them on that name.
          </Text>
          <Table>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Name</Table.Th>
                <Table.Th>Type</Table.Th>
                <Table.Th>Page</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {reverse.map((r) => (
                <Table.Tr key={r.id}>
                  <Table.Td>
                    <UsageLink id={r.usageId} name={r.usageName} onNavigate={onNavigate} />
                  </Table.Td>
                  <Table.Td>{r.type ?? '—'}</Table.Td>
                  <Table.Td>{r.page ?? '—'}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Stack>
      )}
    </Stack>
  );
}
