import { Stack } from '@mantine/core';
import { childApi } from '../api/childApi';
import ChildEntityTab, { type ColumnDef, type FieldDef } from './ChildEntityTab';
import DistributionMapPanel from './map/DistributionMapPanel';
import { referenceOptions } from './NameRelationsTab';

// The 5 taxon-level child entities (accepted-only; the backend guards create and demote drops
// them). Each is a thin ChildEntityTab config over the generic childApi factory. See the
// child-entities spec.

const opt = (values: string[]) => values.map((v) => ({ value: v, label: v }));

interface TabProps {
  pid: number;
  usageId: number;
  canEdit: boolean;
}

// ---- Vernacular ----
interface Vernacular {
  id: number;
  usageId: number;
  name: string | null;
  language: string | null;
  country: string | null;
  sex: string | null;
  preferred: boolean | null;
  referenceId: number | null;
  remarks: string | null;
  version: number;
}
const vernacularApi = childApi<Vernacular>('vernaculars');

export function VernacularTab({ pid, usageId, canEdit }: TabProps) {
  const columns: ColumnDef<Vernacular>[] = [
    { header: 'Name', cell: (r) => r.name ?? '—' },
    { header: 'Language', cell: (r) => r.language ?? '—' },
    { header: 'Preferred', cell: (r) => (r.preferred ? 'Yes' : '—') },
  ];
  const fields: FieldDef<Vernacular>[] = [
    { name: 'name', label: 'Name', span: 6 },
    { name: 'language', label: 'Language', span: 3 },
    { name: 'country', label: 'Country', span: 3 },
    { name: 'sex', label: 'Sex', type: 'select', options: opt(['male', 'female']), span: 3 },
    { name: 'preferred', label: 'Preferred', type: 'boolean', span: 3 },
    {
      name: 'referenceId',
      label: 'Reference',
      type: 'entity',
      load: referenceOptions(pid),
      entityQueryKey: ['refOptions', pid],
      span: 6,
    },
    { name: 'remarks', label: 'Remarks', type: 'textarea', span: 12 },
  ];
  return (
    <ChildEntityTab<Vernacular>
      pid={pid}
      usageId={usageId}
      canEdit={canEdit}
      entity="vernacular name"
      api={vernacularApi}
      columns={columns}
      fields={fields}
      rowId={(r) => r.id}
      rowVersion={(r) => r.version}
      toForm={(r) => ({
        name: r.name ?? '',
        language: r.language ?? '',
        country: r.country ?? '',
        sex: r.sex ?? '',
        preferred: r.preferred == null ? '' : String(r.preferred),
        referenceId: r.referenceId ? String(r.referenceId) : '',
        remarks: r.remarks ?? '',
      })}
      describe={(r) => `Delete the vernacular name ${r.name ?? ''}.`}
    />
  );
}

// ---- Distribution ----
interface Distribution {
  id: number;
  usageId: number;
  area: string | null;
  areaId: string | null;
  gazetteer: string | null;
  establishmentMeans: string | null;
  threatStatus: string | null;
  referenceId: number | null;
  remarks: string | null;
  version: number;
}
const distributionApi = childApi<Distribution>('distributions');

export function DistributionTab({ pid, usageId, canEdit }: TabProps) {
  const columns: ColumnDef<Distribution>[] = [
    { header: 'Area', cell: (r) => r.area ?? r.areaId ?? '—' },
    { header: 'Gazetteer', cell: (r) => r.gazetteer ?? '—' },
    { header: 'Establishment', cell: (r) => r.establishmentMeans ?? '—' },
  ];
  const fields: FieldDef<Distribution>[] = [
    // Either a free-text area OR a gazetteer-coded areaId + gazetteer (preferred, enables maps).
    { name: 'area', label: 'Area (free text)', span: 6 },
    { name: 'areaId', label: 'Area ID', span: 3 },
    {
      name: 'gazetteer',
      label: 'Gazetteer',
      type: 'select',
      options: opt(['iso', 'tdwg', 'fao', 'gadm', 'text']),
      span: 3,
    },
    {
      name: 'establishmentMeans',
      label: 'Establishment means',
      type: 'select',
      options: opt(['native', 'introduced', 'naturalised', 'invasive', 'managed', 'uncertain']),
      span: 6,
    },
    { name: 'threatStatus', label: 'Threat status', span: 6 },
    {
      name: 'referenceId',
      label: 'Reference',
      type: 'entity',
      load: referenceOptions(pid),
      entityQueryKey: ['refOptions', pid],
      span: 8,
    },
    { name: 'remarks', label: 'Remarks', type: 'textarea', span: 12 },
  ];
  return (
    <Stack>
      <DistributionMapPanel pid={pid} usageId={usageId} canEdit={canEdit} />
      <ChildEntityTab<Distribution>
        pid={pid}
        usageId={usageId}
        canEdit={canEdit}
        entity="distribution"
        api={distributionApi}
        columns={columns}
        fields={fields}
        rowId={(r) => r.id}
        rowVersion={(r) => r.version}
        toForm={(r) => ({
          area: r.area ?? '',
          areaId: r.areaId ?? '',
          gazetteer: r.gazetteer ?? '',
          establishmentMeans: r.establishmentMeans ?? '',
          threatStatus: r.threatStatus ?? '',
          referenceId: r.referenceId ? String(r.referenceId) : '',
          remarks: r.remarks ?? '',
        })}
        describe={(r) => `Delete the distribution for ${r.area ?? r.areaId ?? 'this area'}.`}
      />
    </Stack>
  );
}

// ---- Media ----
interface Media {
  id: number;
  usageId: number;
  url: string | null;
  type: string | null;
  title: string | null;
  creator: string | null;
  license: string | null;
  link: string | null;
  remarks: string | null;
  version: number;
}
const mediaApi = childApi<Media>('media');

export function MediaTab({ pid, usageId, canEdit }: TabProps) {
  const columns: ColumnDef<Media>[] = [
    { header: 'Type', cell: (r) => r.type ?? '—' },
    { header: 'Title', cell: (r) => r.title ?? '—' },
    { header: 'URL', cell: (r) => r.url ?? '—' },
  ];
  const fields: FieldDef<Media>[] = [
    { name: 'url', label: 'URL', span: 8 },
    { name: 'type', label: 'Type', type: 'select', options: opt(['image', 'video', 'audio']), span: 4 },
    { name: 'title', label: 'Title', span: 6 },
    { name: 'creator', label: 'Creator', span: 6 },
    { name: 'license', label: 'License', span: 6 },
    { name: 'link', label: 'Link', span: 6 },
    { name: 'remarks', label: 'Remarks', type: 'textarea', span: 12 },
  ];
  return (
    <ChildEntityTab<Media>
      pid={pid}
      usageId={usageId}
      canEdit={canEdit}
      entity="media item"
      api={mediaApi}
      columns={columns}
      fields={fields}
      rowId={(r) => r.id}
      rowVersion={(r) => r.version}
      toForm={(r) => ({
        url: r.url ?? '',
        type: r.type ?? '',
        title: r.title ?? '',
        creator: r.creator ?? '',
        license: r.license ?? '',
        link: r.link ?? '',
        remarks: r.remarks ?? '',
      })}
      describe={(r) => `Delete the media item ${r.title ?? r.url ?? ''}.`}
    />
  );
}

// ---- Estimate ----
interface Estimate {
  id: number;
  usageId: number;
  estimate: number | null;
  type: string | null;
  referenceId: number | null;
  remarks: string | null;
  version: number;
}
const estimateApi = childApi<Estimate>('estimates');

export function EstimateTab({ pid, usageId, canEdit }: TabProps) {
  const columns: ColumnDef<Estimate>[] = [
    { header: 'Estimate', cell: (r) => (r.estimate == null ? '—' : r.estimate.toLocaleString()) },
    { header: 'Type', cell: (r) => r.type ?? '—' },
  ];
  const fields: FieldDef<Estimate>[] = [
    { name: 'estimate', label: 'Estimate', type: 'number', span: 4 },
    { name: 'type', label: 'Type', span: 8 },
    {
      name: 'referenceId',
      label: 'Reference',
      type: 'entity',
      load: referenceOptions(pid),
      entityQueryKey: ['refOptions', pid],
      span: 8,
    },
    { name: 'remarks', label: 'Remarks', type: 'textarea', span: 12 },
  ];
  return (
    <ChildEntityTab<Estimate>
      pid={pid}
      usageId={usageId}
      canEdit={canEdit}
      entity="estimate"
      api={estimateApi}
      columns={columns}
      fields={fields}
      rowId={(r) => r.id}
      rowVersion={(r) => r.version}
      toForm={(r) => ({
        estimate: r.estimate == null ? '' : String(r.estimate),
        type: r.type ?? '',
        referenceId: r.referenceId ? String(r.referenceId) : '',
        remarks: r.remarks ?? '',
      })}
      describe={(r) => `Delete this estimate (${r.estimate ?? ''}).`}
    />
  );
}

// ---- Property ----
interface Property {
  id: number;
  usageId: number;
  property: string | null;
  value: string | null;
  page: string | null;
  referenceId: number | null;
  remarks: string | null;
  version: number;
}
const propertyApi = childApi<Property>('properties');

export function PropertyTab({ pid, usageId, canEdit }: TabProps) {
  const columns: ColumnDef<Property>[] = [
    { header: 'Property', cell: (r) => r.property ?? '—' },
    { header: 'Value', cell: (r) => r.value ?? '—' },
  ];
  const fields: FieldDef<Property>[] = [
    { name: 'property', label: 'Property', span: 4 },
    { name: 'value', label: 'Value', span: 4 },
    { name: 'page', label: 'Page', span: 4 },
    {
      name: 'referenceId',
      label: 'Reference',
      type: 'entity',
      load: referenceOptions(pid),
      entityQueryKey: ['refOptions', pid],
      span: 8,
    },
    { name: 'remarks', label: 'Remarks', type: 'textarea', span: 12 },
  ];
  return (
    <ChildEntityTab<Property>
      pid={pid}
      usageId={usageId}
      canEdit={canEdit}
      entity="property"
      api={propertyApi}
      columns={columns}
      fields={fields}
      rowId={(r) => r.id}
      rowVersion={(r) => r.version}
      toForm={(r) => ({
        property: r.property ?? '',
        value: r.value ?? '',
        page: r.page ?? '',
        referenceId: r.referenceId ? String(r.referenceId) : '',
        remarks: r.remarks ?? '',
      })}
      describe={(r) => `Delete the property ${r.property ?? ''}.`}
    />
  );
}
