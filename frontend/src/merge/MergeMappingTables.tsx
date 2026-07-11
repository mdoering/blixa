import { Badge, Button, Chip, Group, Modal, Stack, Tabs, Text, TextInput } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { notifications } from '@mantine/notifications';
import { MantineReactTable, useMantineReactTable, type MRT_ColumnDef } from 'mantine-react-table';
import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getMergeMapping,
  putMergeOverrides,
  type MappingRow,
  type MergeCategory,
  type MergeOverride,
} from '../api/merge';
import { searchUsages } from '../api/usages';
import { listReferences } from '../api/references';
import { messageFor } from '../api/client';

export interface MergeMappingTablesProps {
  targetId: number;
  runId: number;
}

type Entity = 'name' | 'reference';

// Backend page cap (MergeController#mapping's `size` @RequestParam, clamped server-side to 200).
// getMapping has no `total` count to drive mantine-react-table's server-side rowCount, so each
// category filter is fetched once (up to this cap) and paginated/sorted client-side in the table
// below. A single category with more than 200 candidates only shows the first page -- acceptable
// for a curator review table, not a bulk export.
const PAGE_SIZE = 200;

const NAME_CATEGORIES: MergeCategory[] = [
  'MATCHED',
  'POSSIBLE_HOMONYM',
  'POSSIBLE_FUZZY',
  'POSSIBLE',
  'NEW',
];
const REFERENCE_CATEGORIES: MergeCategory[] = ['MATCHED', 'POSSIBLE', 'NEW'];

const CATEGORY_LABELS: Record<MergeCategory, string> = {
  MATCHED: 'Matched',
  POSSIBLE_HOMONYM: 'Possible homonym',
  POSSIBLE_FUZZY: 'Possible fuzzy',
  POSSIBLE: 'Possible',
  NEW: 'New',
};

const CATEGORY_COLORS: Record<MergeCategory, string> = {
  MATCHED: 'blue',
  POSSIBLE_HOMONYM: 'yellow',
  POSSIBLE_FUZZY: 'orange',
  POSSIBLE: 'orange',
  NEW: 'green',
};

const CONFIRMABLE: MergeCategory[] = ['POSSIBLE_HOMONYM', 'POSSIBLE_FUZZY', 'POSSIBLE'];

// A curator's queued-but-not-yet-saved override, extended with the re-pointed target's display
// label (client-only -- stripped before the PUT, see save() below) so the row can preview the
// queued change without waiting for a save + refetch round-trip.
interface PendingOverride extends MergeOverride {
  targetLabel?: string | null;
}

function overrideKey(entity: Entity, sourceId: string): string {
  return `${entity}:${sourceId}`;
}

// The per-row correction surface for one PLANNED run's mapping (Task 10): two peer tabs (Names,
// References), each a mantine-react-table fed by getMergeMapping, filterable by category chip.
// Confirm/Reject/Re-point queue local PendingOverride entries (shared across both tabs, since one
// putMergeOverrides PUT can carry both entities' corrections); "Save overrides" flushes the batch,
// invalidates the mapping queries (rows changed) and seeds the ['mergeRun', targetId, runId] query
// MergeModal polls with the PUT response's already-recomputed metrics, so the Impact metrics
// reflect the correction without a manual reopen or an extra GET.
export default function MergeMappingTables({ targetId, runId }: MergeMappingTablesProps) {
  const queryClient = useQueryClient();
  const [pending, setPending] = useState<Record<string, PendingOverride>>({});
  const pendingCount = Object.keys(pending).length;

  function addOverride(o: PendingOverride) {
    setPending((p) => ({ ...p, [overrideKey(o.entity, o.sourceId)]: o }));
  }
  function removeOverride(entity: Entity, sourceId: string) {
    setPending((p) => {
      const next = { ...p };
      delete next[overrideKey(entity, sourceId)];
      return next;
    });
  }

  const saveMut = useMutation({
    mutationFn: () =>
      putMergeOverrides(
        targetId,
        runId,
        // targetLabel is a display-only convenience for the pending-row preview -- not part of the
        // wire MergeOverride shape, so it's stripped before the PUT.
        Object.values(pending).map(({ targetLabel: _targetLabel, ...o }) => o),
      ),
    onSuccess: (updatedRun) => {
      setPending({});
      queryClient.invalidateQueries({ queryKey: ['mergeMapping', targetId, runId] });
      // The PUT response already carries the recomputed metrics -- seed the poll query's cache with
      // it directly (same pattern as MergeModal's applyMut.onSuccess) instead of invalidating and
      // triggering an extra GET for a run that isn't RUNNING/APPLYING (so refetchInterval won't get
      // it for free either).
      queryClient.setQueryData(['mergeRun', targetId, runId], updatedRun);
      notifications.show({ color: 'green', message: 'Overrides saved' });
    },
    onError: (e) =>
      notifications.show({ color: 'red', message: messageFor(e, 'Failed to save overrides') }),
  });

  return (
    <Stack gap="sm">
      <Tabs defaultValue="name" keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="name">Names</Tabs.Tab>
          <Tabs.Tab value="reference">References</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="name" pt="sm">
          <MappingTable
            entity="name"
            targetId={targetId}
            runId={runId}
            categories={NAME_CATEGORIES}
            pending={pending}
            onAddOverride={addOverride}
            onRemoveOverride={removeOverride}
          />
        </Tabs.Panel>
        <Tabs.Panel value="reference" pt="sm">
          <MappingTable
            entity="reference"
            targetId={targetId}
            runId={runId}
            categories={REFERENCE_CATEGORIES}
            pending={pending}
            onAddOverride={addOverride}
            onRemoveOverride={removeOverride}
          />
        </Tabs.Panel>
      </Tabs>

      <Group justify="space-between">
        <Text size="sm" c="dimmed">
          {pendingCount} pending edit{pendingCount === 1 ? '' : 's'}
        </Text>
        <Group gap="xs">
          <Button
            variant="default"
            size="xs"
            disabled={pendingCount === 0}
            onClick={() => setPending({})}
          >
            Discard
          </Button>
          <Button
            size="xs"
            disabled={pendingCount === 0}
            loading={saveMut.isPending}
            onClick={() => saveMut.mutate()}
          >
            Save overrides
          </Button>
        </Group>
      </Group>
    </Stack>
  );
}

interface MappingTableProps {
  entity: Entity;
  targetId: number;
  runId: number;
  categories: MergeCategory[];
  pending: Record<string, PendingOverride>;
  onAddOverride: (o: PendingOverride) => void;
  onRemoveOverride: (entity: Entity, sourceId: string) => void;
}

function MappingTable({
  entity,
  targetId,
  runId,
  categories,
  pending,
  onAddOverride,
  onRemoveOverride,
}: MappingTableProps) {
  const [category, setCategory] = useState<MergeCategory | null>(null);
  const [pickerFor, setPickerFor] = useState<MappingRow | null>(null);

  const { data, isLoading, isFetching } = useQuery({
    queryKey: ['mergeMapping', targetId, runId, entity, category],
    queryFn: () => getMergeMapping(targetId, runId, entity, category ?? undefined, 0, PAGE_SIZE),
  });

  const columns = useMemo<MRT_ColumnDef<MappingRow>[]>(
    () => [
      {
        accessorKey: 'sourceLabel',
        header: 'Source',
        Cell: ({ row }) => row.original.sourceLabel ?? row.original.sourceId,
      },
      {
        id: 'category',
        header: 'Category',
        Cell: ({ row }) => {
          const p = pending[overrideKey(entity, row.original.sourceId)];
          const cat = p?.category ?? row.original.category;
          return (
            <Group gap={4} wrap="nowrap">
              <Badge color={CATEGORY_COLORS[cat]} variant="light">
                {CATEGORY_LABELS[cat]}
              </Badge>
              {p && (
                <Badge color="gray" variant="outline">
                  pending
                </Badge>
              )}
            </Group>
          );
        },
      },
      {
        id: 'target',
        header: 'Target',
        Cell: ({ row }) => {
          const p = pending[overrideKey(entity, row.original.sourceId)];
          if (p) return p.category === 'NEW' ? '—' : (p.targetLabel ?? p.targetId ?? '—');
          return row.original.targetLabel ?? row.original.targetId ?? '—';
        },
      },
      {
        accessorKey: 'score',
        header: 'Score',
        Cell: ({ cell }) => {
          const v = cell.getValue<number | null>();
          return v == null ? '—' : v.toFixed(2);
        },
      },
      {
        id: 'actions',
        header: 'Actions',
        Cell: ({ row }) => {
          const original = row.original;
          const p = pending[overrideKey(entity, original.sourceId)];
          if (p) {
            return (
              <Button
                size="xs"
                variant="subtle"
                color="gray"
                onClick={() => onRemoveOverride(entity, original.sourceId)}
              >
                Undo
              </Button>
            );
          }
          return (
            <Group gap={4} wrap="nowrap">
              {CONFIRMABLE.includes(original.category) && (
                <Button
                  size="xs"
                  variant="light"
                  color="blue"
                  onClick={() =>
                    onAddOverride({
                      entity,
                      sourceId: original.sourceId,
                      category: 'MATCHED',
                      targetId: original.targetId,
                      targetLabel: original.targetLabel,
                    })
                  }
                >
                  Confirm
                </Button>
              )}
              {original.category === 'MATCHED' && (
                <Button
                  size="xs"
                  variant="light"
                  color="red"
                  onClick={() =>
                    onAddOverride({
                      entity,
                      sourceId: original.sourceId,
                      category: 'NEW',
                      targetId: null,
                    })
                  }
                >
                  Reject
                </Button>
              )}
              <Button size="xs" variant="default" onClick={() => setPickerFor(original)}>
                Re-point
              </Button>
            </Group>
          );
        },
      },
    ],
    [pending, entity, onAddOverride, onRemoveOverride],
  );

  const table = useMantineReactTable({
    columns,
    data: data ?? [],
    getRowId: (row) => row.sourceId,
    enableColumnActions: false,
    enableColumnFilters: false,
    enableTopToolbar: false,
    state: { isLoading, showProgressBars: isFetching },
  });

  return (
    <Stack gap="xs">
      <Chip.Group
        value={category ?? 'ALL'}
        onChange={(v) => setCategory(v === 'ALL' ? null : (v as MergeCategory))}
      >
        <Group gap={6}>
          <Chip value="ALL" size="xs">
            All
          </Chip>
          {categories.map((c) => (
            <Chip key={c} value={c} size="xs">
              {CATEGORY_LABELS[c]}
            </Chip>
          ))}
        </Group>
      </Chip.Group>
      <MantineReactTable table={table} />
      {pickerFor && (
        <TargetPicker
          entity={entity}
          targetId={targetId}
          onClose={() => setPickerFor(null)}
          onPick={(id, label) => {
            onAddOverride({
              entity,
              sourceId: pickerFor.sourceId,
              category: 'MATCHED',
              targetId: id,
              targetLabel: label,
            });
            setPickerFor(null);
          }}
        />
      )}
    </Stack>
  );
}

function nameLabel(u: { scientificName: string | null; authorship: string | null; rank: string | null }) {
  const parts = [u.scientificName, u.authorship].filter(Boolean).join(' ');
  return u.rank ? `${parts} (${u.rank})` : parts;
}

// The re-point picker: a target-usage search for names, a target-reference search for references
// (both reuse existing search endpoints -- searchUsages/listReferences -- scoped to the TARGET
// project, per the brief's "prefer reusing an existing search"). Picking a result queues a MATCHED
// override pointing at it.
function TargetPicker({
  entity,
  targetId,
  onClose,
  onPick,
}: {
  entity: Entity;
  targetId: number;
  onClose: () => void;
  onPick: (id: string, label: string) => void;
}) {
  const [q, setQ] = useState('');
  const [debouncedQ] = useDebouncedValue(q, 300);

  const { data: usagePage } = useQuery({
    queryKey: ['mergeTargetUsageSearch', targetId, debouncedQ],
    queryFn: () => searchUsages(targetId, { q: debouncedQ.trim() || undefined, limit: 10, offset: 0 }),
    enabled: entity === 'name',
  });
  const { data: refs } = useQuery({
    queryKey: ['mergeTargetRefSearch', targetId, debouncedQ],
    queryFn: () => listReferences(targetId, { q: debouncedQ.trim() || undefined, limit: 10, offset: 0 }),
    enabled: entity === 'reference',
  });

  const results: { id: string; label: string }[] =
    entity === 'name'
      ? (usagePage?.items ?? []).map((u) => ({ id: String(u.id), label: nameLabel(u) }))
      : (refs ?? []).map((r) => ({ id: String(r.id), label: r.citation ?? `#${r.id}` }));

  return (
    <Modal opened onClose={onClose} title={`Pick a target ${entity}`} size="sm">
      <Stack gap="xs">
        <TextInput
          placeholder={entity === 'name' ? 'Search target names…' : 'Search target references…'}
          value={q}
          onChange={(e) => setQ(e.currentTarget.value)}
          data-autofocus
        />
        <Stack gap={4} mah={300} style={{ overflowY: 'auto' }}>
          {results.length === 0 && (
            <Text size="sm" c="dimmed">
              No matches.
            </Text>
          )}
          {results.map((r) => (
            <Button
              key={r.id}
              variant="subtle"
              justify="flex-start"
              onClick={() => onPick(r.id, r.label)}
            >
              {r.label}
            </Button>
          ))}
        </Stack>
      </Stack>
    </Modal>
  );
}
