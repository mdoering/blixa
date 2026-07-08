import { Box, Button, Grid, Group, Select, Text, TextInput } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconPlus, IconSearch } from '@tabler/icons-react';
import { MantineReactTable, useMantineReactTable, type MRT_ColumnDef } from 'mantine-react-table';
import { useEffect, useMemo, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { getProject } from '../api/projects';
import { searchUsages } from '../api/usages';
import type { NameUsage } from '../api/types';
import TaxonDetail from '../tree/TaxonDetail';
import CreateNameModal from './CreateNameModal';
import NameActionMenu from './NameActionMenu';
import { useNameActions } from './useNameActions';

// Same common ranks as CreateNameModal's RANK_OPTIONS (lower-case wire form).
const RANK_OPTIONS = [
  'kingdom',
  'phylum',
  'class',
  'order',
  'family',
  'genus',
  'species',
  'subspecies',
  'variety',
  'form',
].map((r) => ({ value: r, label: r }));

// Same 4 statuses as TaxonDetail/NameActionMenu's STATUS_OPTIONS (upper-case enum-name wire form).
const STATUS_OPTIONS = [
  { value: 'ACCEPTED', label: 'Accepted' },
  { value: 'SYNONYM', label: 'Synonym' },
  { value: 'MISAPPLIED', label: 'Misapplied' },
  { value: 'UNASSESSED', label: 'Unassessed' },
];

const DEFAULT_PAGE_SIZE = 10;

// Flat, filterable search over all name usages in the project -- the other way (besides the
// classification Tree) to find a name and select it into the shared TaxonDetail panel. Table
// pagination/filtering are server-side (the project's usages can far exceed what's reasonable to
// filter/paginate client-side), driven by the extended GET /usages?q=&rank=&status=&limit=&offset=
// (see api/usages.ts#searchUsages and backend UsagePage).
export default function NameSearchPage() {
  const { projectId } = useParams();
  const pid = Number(projectId);

  const { data: project } = useQuery({ queryKey: ['project', pid], queryFn: () => getProject(pid) });
  const canEdit = project ? ['owner', 'editor'].includes(project.role) : false;

  const [selectedId, setSelectedId] = useState<number | null>(null);
  // Which row's ⋮ menu is open -- a single id, since only one menu can be open at a time; also
  // driven by right-click on the row (mirrors TreeNodeRow's onContextMenu handling).
  const [menuOpenId, setMenuOpenId] = useState<number | null>(null);

  const [q, setQ] = useState('');
  const [debouncedQ] = useDebouncedValue(q, 300);
  const [rank, setRank] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [pagination, setPagination] = useState({ pageIndex: 0, pageSize: DEFAULT_PAGE_SIZE });

  // A filter change invalidates whatever page the user was on -- go back to the first page rather
  // than showing (possibly) an out-of-range, empty page.
  useEffect(() => {
    setPagination((p) => (p.pageIndex === 0 ? p : { ...p, pageIndex: 0 }));
  }, [debouncedQ, rank, status]);

  const actions = useNameActions(pid);

  const queryParams = {
    q: debouncedQ.trim() || undefined,
    rank: rank ?? undefined,
    status: status ?? undefined,
    limit: pagination.pageSize,
    offset: pagination.pageIndex * pagination.pageSize,
  };

  const { data, isLoading, isFetching } = useQuery({
    queryKey: ['usageSearch', pid, queryParams],
    queryFn: () => searchUsages(pid, queryParams),
    placeholderData: keepPreviousData,
  });

  const columns = useMemo<MRT_ColumnDef<NameUsage>[]>(
    () => [
      {
        accessorKey: 'scientificName',
        header: 'Scientific name',
        Cell: ({ row }) => (
          <Group gap={6} wrap="nowrap">
            <Text size="sm" fs="italic">
              {row.original.scientificName}
            </Text>
            {row.original.authorship && (
              <Text size="xs" c="dimmed" truncate>
                {row.original.authorship}
              </Text>
            )}
          </Group>
        ),
      },
      {
        accessorKey: 'rank',
        header: 'Rank',
        Cell: ({ cell }) => cell.getValue<string | null>() ?? '—',
      },
      {
        accessorKey: 'status',
        header: 'Status',
        Cell: ({ cell }) => cell.getValue<string | null>() ?? '—',
      },
    ],
    [],
  );

  const table = useMantineReactTable({
    columns,
    data: data?.items ?? [],
    getRowId: (row) => String(row.id),
    manualPagination: true,
    manualFiltering: true,
    rowCount: data?.total ?? 0,
    onPaginationChange: setPagination,
    state: { pagination, isLoading, showProgressBars: isFetching },
    enableColumnActions: false,
    enableColumnFilters: false,
    enableSorting: false,
    enableTopToolbar: false,
    enableRowActions: canEdit,
    positionActionsColumn: 'last',
    renderRowActions: ({ row }) => (
      <NameActionMenu
        pid={pid}
        usage={row.original}
        canEdit={canEdit}
        onSelect={setSelectedId}
        opened={menuOpenId === row.original.id}
        onChange={(opened) => setMenuOpenId(opened ? row.original.id : null)}
        onAfterDelete={(id) => {
          if (id === selectedId) setSelectedId(null);
        }}
      />
    ),
    mantineTableBodyRowProps: ({ row }) => ({
      onClick: () => setSelectedId(row.original.id),
      onContextMenu: (e) => {
        if (!canEdit) return;
        e.preventDefault();
        setMenuOpenId(row.original.id);
      },
      style: {
        cursor: 'pointer',
        backgroundColor:
          selectedId === row.original.id ? 'var(--mantine-color-blue-light)' : undefined,
      },
    }),
  });

  return (
    <Box>
      <Group justify="space-between" mb="md">
        <Text fw={600}>Names</Text>
        {canEdit && (
          <Button leftSection={<IconPlus size={14} />} size="xs" onClick={() => actions.createRoot()}>
            New name
          </Button>
        )}
      </Group>
      <Group mb="md">
        <TextInput
          placeholder="Search scientific name…"
          leftSection={<IconSearch size={14} />}
          value={q}
          onChange={(e) => setQ(e.currentTarget.value)}
          w={260}
        />
        <Select
          placeholder="Rank"
          data={RANK_OPTIONS}
          value={rank}
          onChange={setRank}
          clearable
          w={160}
        />
        <Select
          placeholder="Status"
          data={STATUS_OPTIONS}
          value={status}
          onChange={setStatus}
          clearable
          w={160}
        />
      </Group>
      <Grid gutter="md">
        <Grid.Col span={7}>
          <MantineReactTable table={table} />
        </Grid.Col>
        <Grid.Col span={5}>
          {selectedId == null ? (
            <Text c="dimmed">Select a name to see its details.</Text>
          ) : (
            <TaxonDetail pid={pid} usageId={selectedId} />
          )}
        </Grid.Col>
      </Grid>
      {actions.modalState && (
        <CreateNameModal
          pid={pid}
          mode={actions.modalState.mode}
          anchor={actions.modalState.anchor}
          opened
          onClose={actions.closeModal}
          onCreated={(newId) => {
            actions.closeModal();
            setSelectedId(newId);
          }}
        />
      )}
    </Box>
  );
}
