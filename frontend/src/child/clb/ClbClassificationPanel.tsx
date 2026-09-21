import { Alert, Badge, Button, Checkbox, Group, Loader, Stack, Table, Text, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { applyClbClassification, previewClbClassification, type ClbClassificationStep } from '../../api/clb';
import { messageFor } from '../../api/client';

// Missing ancestors of these ranks are pre-selected for creation; COL's many intermediate ranks
// (superfamily, infraclass, parvphylum, …) are left for the editor to opt into.
const MAIN_RANKS = new Set(['kingdom', 'phylum', 'class', 'order', 'family', 'genus']);

interface Props {
  pid: number;
  usageId: number;
  usageName: string;
  datasetKey: string;
  taxonId: string;
  // unsaved values copied into the edit form -- moving reloads the name, which would drop them
  blockedReason?: string;
  onDone: () => void;
  onCancel: () => void;
}

// "Wire into tree": the CLB higher classification resolved against our tree. Existing taxa are used
// as-is; missing ones can be created (checkbox); the focal name is then moved under the lowest
// resolved one. See docs/superpowers/specs/2026-09-21-clb-compare-copy-classification-relations-design.md.
export default function ClbClassificationPanel({
  pid,
  usageId,
  usageName,
  datasetKey,
  taxonId,
  blockedReason,
  onDone,
  onCancel,
}: Props) {
  const queryClient = useQueryClient();
  const preview = useQuery({
    queryKey: ['clbClassification', pid, usageId, datasetKey, taxonId],
    queryFn: () => previewClbClassification(pid, usageId, datasetKey, taxonId),
    staleTime: 0,
  });
  const steps = useMemo(() => preview.data?.steps ?? [], [preview.data]);

  // Only missing ranks below the lowest existing match can be created (same rule as the server):
  // existing taxa aren't moved, so anything created above one would be an empty branch.
  const lastMatch = steps.reduce((acc, s, i) => (s.matchId != null ? i : acc), -1);
  const creatable = (i: number) => i > lastMatch && !!steps[i].clbId;

  const [create, setCreate] = useState<Set<string>>(new Set());
  useEffect(() => {
    setCreate(
      new Set(steps.filter((s, i) => s.matchId == null && creatable(i) && MAIN_RANKS.has(s.rank)).map((s) => s.clbId as string)),
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [steps]);

  // Where the name ends up: under the lowest step that exists or will be created.
  const target = [...steps].reverse().find((s) => s.matchId != null || (s.clbId && create.has(s.clbId)));
  const unchanged = target?.matchId != null && target.matchId === preview.data?.currentParentId;

  const apply = useMutation({
    mutationFn: () => applyClbClassification(pid, usageId, { datasetKey, taxonId, createClbIds: [...create] }),
    onSuccess: async (r) => {
      for (const key of [['usage', pid, usageId], ['path', pid, usageId], ['treeRoots', pid], ['treeChildren', pid], ['treePath', pid], ['changes', pid]]) {
        await queryClient.invalidateQueries({ queryKey: key });
      }
      notifications.show({
        message: `${r.moved ? 'Moved into the tree' : 'Already in place'}${r.created ? ` — created ${r.created} higher taxa` : ''}`,
      });
      onDone();
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not wire into the tree') }),
  });

  const status = (s: ClbClassificationStep, i: number) => {
    if (s.matchId != null) {
      return s.inPlace ? (
        <Badge size="xs" color="green" variant="light">in tree</Badge>
      ) : (
        <Tooltip label={s.ambiguous ? 'Several taxa with this name — the first is used' : 'Exists elsewhere in our tree — used as-is, not moved'} withArrow multiline maw={260}>
          <Badge size="xs" color="yellow" variant="light">{s.ambiguous ? 'ambiguous' : 'exists elsewhere'}</Badge>
        </Tooltip>
      );
    }
    if (!creatable(i)) {
      return (
        <Tooltip label="Above a taxon that already exists in our tree (which isn't moved) — it would stay an empty branch" withArrow multiline maw={260}>
          <Badge size="xs" color="gray" variant="light">not in tree</Badge>
        </Tooltip>
      );
    }
    return (
      <Checkbox
        size="xs"
        label="create"
        aria-label={`Create ${s.name}`}
        checked={!!s.clbId && create.has(s.clbId)}
        onChange={(e) => {
          const next = new Set(create);
          if (e.currentTarget.checked) next.add(s.clbId as string);
          else next.delete(s.clbId as string);
          setCreate(next);
        }}
      />
    );
  };

  if (preview.isLoading) return <Loader />;
  if (preview.isError) return <Text c="red">{messageFor(preview.error, 'Could not load the classification')}</Text>;

  return (
    <Stack>
      <Text size="sm">
        Existing higher taxa are used as they are; missing ones can be created. <i>{usageName}</i> then moves
        under the lowest of them.
      </Text>
      <Table verticalSpacing={4}>
        <Table.Tbody>
          {steps.map((s, i) => (
            <Table.Tr key={`${s.clbId}-${i}`}>
              <Table.Td w={120}>
                <Text size="sm" c="dimmed">{s.rank}</Text>
              </Table.Td>
              <Table.Td>
                <Text size="sm">
                  {s.name} {s.authorship && <Text span c="dimmed" inherit>{s.authorship}</Text>}
                </Text>
              </Table.Td>
              <Table.Td w={150}>{status(s, i)}</Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
      <Text size="sm">
        {target ? (
          unchanged ? (
            <>Already under <b>{target.name}</b> — nothing to move.</>
          ) : (
            <>
              <i>{usageName}</i> moves under <b>{target.name}</b>
              {target.matchId == null && ' (new)'}
              {preview.data?.currentParentName && <> (now under {preview.data.currentParentName})</>}.
            </>
          )
        ) : (
          'Nothing selected — the name stays where it is.'
        )}
      </Text>
      {blockedReason && <Alert color="orange" variant="light">{blockedReason}</Alert>}
      <Group justify="flex-end">
        <Button variant="default" onClick={onCancel}>Back</Button>
        <Button
          onClick={() => apply.mutate()}
          loading={apply.isPending}
          disabled={!!blockedReason || !target || (unchanged && create.size === 0)}
        >
          Wire into tree
        </Button>
      </Group>
    </Stack>
  );
}
