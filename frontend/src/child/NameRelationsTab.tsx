import {
  createNameRelation,
  deleteNameRelation,
  listNameRelations,
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

export default function NameRelationsTab({
  pid,
  usageId,
  canEdit,
}: {
  pid: number;
  usageId: number;
  canEdit: boolean;
}) {
  const columns: ColumnDef<NameRelation>[] = [
    { header: 'Type', cell: (r) => r.type ?? '—' },
    {
      header: 'Related name',
      cell: (r) => r.relatedName ?? (r.relatedUsageId ? `#${r.relatedUsageId}` : '—'),
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
  );
}
