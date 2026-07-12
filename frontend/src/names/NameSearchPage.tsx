import { Badge, Box, Button, Grid, Group, Select, Text, TextInput, Tooltip } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconPlus, IconSearch } from '@tabler/icons-react';
import {
  MantineReactTable,
  useMantineReactTable,
  type MRT_ColumnDef,
  type MRT_RowSelectionState,
} from 'mantine-react-table';
import { useEffect, useMemo, useState } from 'react';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { useParams, useSearchParams } from 'react-router-dom';
import { getProject } from '../api/projects';
import { searchUsages } from '../api/usages';
import type { NameUsage } from '../api/types';
import MergeRecordsModal from '../merge/MergeRecordsModal';
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

// Compact, colour-coded status chips for the table's narrow Status column: a 3-letter abbreviation
// (scannable by colour, full word on hover) instead of the space-hungry full label.
const STATUS_META: Record<string, { abbr: string; color: string; label: string }> = {
  ACCEPTED: { abbr: 'ACC', color: 'green', label: 'Accepted' },
  SYNONYM: { abbr: 'SYN', color: 'gray', label: 'Synonym' },
  MISAPPLIED: { abbr: 'MIS', color: 'orange', label: 'Misapplied' },
  UNASSESSED: { abbr: 'UNA', color: 'blue', label: 'Unassessed' },
};

const DEFAULT_PAGE_SIZE = 10;

// Flat, filterable search over all name usages in the project -- the other way (besides the
// classification Tree) to find a name and select it into the shared TaxonDetail panel. Table
// pagination/filtering are server-side (the project's usages can far exceed what's reasonable to
// filter/paginate client-side), driven by the extended GET /usages?q=&rank=&status=&limit=&offset=
// (see api/usages.ts#searchUsages and backend UsagePage).
export default function NameSearchPage() {
  const { projectId } = useParams();
  const pid = Number(projectId);
  const queryClient = useQueryClient();

  const { data: project } = useQuery({ queryKey: ['project', pid], queryFn: () => getProject(pid) });
  const canEdit = project ? ['owner', 'editor'].includes(project.role) : false;

  // Multi-select for the "Merge N selected…" action (Names dedupe) -- keyed by row id (a string,
  // per getRowId below), so it survives across pages/filter changes rather than tracking indices.
  const [rowSelection, setRowSelection] = useState<MRT_RowSelectionState>({});
  const [mergeOpen, setMergeOpen] = useState(false);
  const selectedIds = Object.keys(rowSelection).map(Number);

  // Deep-link support: ?usage=<id> (e.g. from the Issues dashboard) preselects that usage's detail,
  // even if it isn't on the current filtered table page. Re-syncs when the param changes.
  const [searchParams] = useSearchParams();
  const usageParam = searchParams.get('usage');
  const [selectedId, setSelectedId] = useState<number | null>(
    usageParam ? Number(usageParam) : null,
  );
  useEffect(() => {
    if (usageParam) setSelectedId(Number(usageParam));
  }, [usageParam]);
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
        grow: true,
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
        size: 96,
        grow: false,
        Cell: ({ cell }) => cell.getValue<string | null>() ?? '—',
      },
      {
        accessorKey: 'status',
        header: 'Status',
        size: 70,
        grow: false,
        Cell: ({ cell }) => {
          const v = cell.getValue<string | null>();
          if (!v) return '—';
          const m = STATUS_META[v] ?? { abbr: v.slice(0, 3), color: 'gray', label: v };
          return (
            <Tooltip label={m.label} withArrow>
              <Badge color={m.color} variant="light" size="sm" radius="sm">
                {m.abbr}
              </Badge>
            </Tooltip>
          );
        },
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
    onRowSelectionChange: setRowSelection,
    state: { pagination, isLoading, showProgressBars: isFetching, rowSelection },
    enableRowSelection: true,
    enableColumnActions: false,
    enableColumnFilters: false,
    enableSorting: false,
    enableTopToolbar: false,
    enableRowActions: canEdit,
    positionActionsColumn: 'last',
    // Flex layout so the sized/grow columns compact predictably: the scientific-name column grows,
    // rank/status/actions stay at their fixed widths.
    layoutMode: 'grid',
    // Drop the "Actions" heading and make the kebab a slim right-hand column.
    displayColumnDefOptions: {
      'mrt-row-actions': {
        header: '',
        size: 48,
        grow: false,
        mantineTableHeadCellProps: { style: { padding: 0 } },
        mantineTableBodyCellProps: { style: { paddingLeft: 4, paddingRight: 4 } },
      },
    },
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
      {selectedIds.length >= 2 && canEdit && (
        <Group mb="md">
          <Button variant="light" size="xs" onClick={() => setMergeOpen(true)}>
            Merge {selectedIds.length} selected…
          </Button>
        </Group>
      )}
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
      <MergeRecordsModal
        entity="usage"
        pid={pid}
        ids={selectedIds}
        opened={mergeOpen}
        onClose={() => setMergeOpen(false)}
        onDone={() => {
          setRowSelection({});
          queryClient.invalidateQueries({ queryKey: ['usageSearch', pid] });
          queryClient.invalidateQueries({ queryKey: ['treeChildren', pid] });
          queryClient.invalidateQueries({ queryKey: ['treeRoots', pid] });
        }}
      />
    </Box>
  );
}
