import {
  createTypeMaterial,
  deleteTypeMaterial,
  listTypeMaterial,
  updateTypeMaterial,
  type TypeMaterial,
} from '../api/typeMaterial';
import ChildEntityTab, { type ColumnDef, type FieldDef } from './ChildEntityTab';
import { referenceOptions } from './NameRelationsTab';

// ColDP TypeStatus (common values); TEXT on the wire.
const TYPE_STATUS = [
  'holotype',
  'lectotype',
  'neotype',
  'syntype',
  'paratype',
  'paralectotype',
  'isotype',
  'topotype',
  'other type',
].map((v) => ({ value: v, label: v }));

const SEX = ['male', 'female', 'hermaphrodite'].map((v) => ({ value: v, label: v }));

export default function TypeMaterialTab({
  pid,
  usageId,
  canEdit,
}: {
  pid: number;
  usageId: number;
  canEdit: boolean;
}) {
  const columns: ColumnDef<TypeMaterial>[] = [
    { header: 'Status', cell: (r) => r.status ?? '—' },
    { header: 'Citation', cell: (r) => r.citation ?? '—' },
    {
      header: 'Institution',
      cell: (r) =>
        [r.institutionCode, r.catalogNumber].filter(Boolean).join(' ') || '—',
    },
    { header: 'Country', cell: (r) => r.country ?? '—' },
  ];

  const fields: FieldDef<TypeMaterial>[] = [
    { name: 'status', label: 'Type status', type: 'select', options: TYPE_STATUS, span: 4 },
    { name: 'citation', label: 'Citation', span: 8 },
    { name: 'institutionCode', label: 'Institution code', span: 4 },
    { name: 'catalogNumber', label: 'Catalog number', span: 4 },
    { name: 'occurrenceId', label: 'GBIF occurrenceID', span: 4 },
    { name: 'locality', label: 'Locality', span: 6 },
    { name: 'country', label: 'Country', span: 3 },
    { name: 'collector', label: 'Collector', span: 3 },
    { name: 'date', label: 'Date', span: 6 },
    { name: 'sex', label: 'Sex', type: 'select', options: SEX, span: 6 },
    {
      name: 'referenceId',
      label: 'Reference',
      type: 'entity',
      load: referenceOptions(pid),
      entityQueryKey: ['refOptions', pid],
      span: 8,
    },
    { name: 'link', label: 'Link', span: 4 },
    { name: 'remarks', label: 'Remarks', type: 'textarea', span: 12 },
  ];

  return (
    <ChildEntityTab<TypeMaterial>
      pid={pid}
      usageId={usageId}
      canEdit={canEdit}
      entity="type material"
      api={{
        list: listTypeMaterial,
        create: createTypeMaterial,
        update: updateTypeMaterial,
        remove: deleteTypeMaterial,
      }}
      columns={columns}
      fields={fields}
      rowId={(r) => r.id}
      rowVersion={(r) => r.version}
      toForm={(r) => ({
        status: r.status ?? '',
        citation: r.citation ?? '',
        institutionCode: r.institutionCode ?? '',
        catalogNumber: r.catalogNumber ?? '',
        occurrenceId: r.occurrenceId ?? '',
        locality: r.locality ?? '',
        country: r.country ?? '',
        collector: r.collector ?? '',
        date: r.date ?? '',
        sex: r.sex ?? '',
        referenceId: r.referenceId ? String(r.referenceId) : '',
        link: r.link ?? '',
        remarks: r.remarks ?? '',
      })}
      describe={(r) => `Delete the ${r.status ?? ''} type material ${r.citation ?? ''}.`}
    />
  );
}
