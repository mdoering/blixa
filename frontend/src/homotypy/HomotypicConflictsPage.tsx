import { Badge, Button, Card, Group, Loader, Radio, Stack, Text, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { messageFor } from '../api/client';
import {
  consolidateHomotypic,
  getHomotypicConflicts,
  type ConflictCluster,
} from '../api/usages';

function clusterKey(c: ConflictCluster) {
  return c.accepted.map((a) => a.id).sort((x, y) => x - y).join('-');
}

// One conflict card: pick a survivor among the accepted candidates; Consolidate demotes the others.
function ConflictCard({
  pid,
  cluster,
  onDone,
}: {
  pid: number;
  cluster: ConflictCluster;
  onDone: () => void;
}) {
  const qc = useQueryClient();
  const [survivor, setSurvivor] = useState<number>(
    cluster.suggestedSurvivorId ?? cluster.accepted[0]?.id,
  );

  const consolidate = useMutation({
    mutationFn: () => {
      const losers = cluster.accepted
        .filter((a) => a.id !== survivor)
        .map((a) => ({ acceptedId: a.id, version: a.version }));
      const relations = cluster.relations.map((r) => ({
        usageId: r.usageId,
        relatedUsageId: r.relatedUsageId,
        type: r.type,
      }));
      return consolidateHomotypic(pid, survivor, { losers, relations });
    },
    onSuccess: async () => {
      await qc.invalidateQueries({ queryKey: ['tree', pid] });
      notifications.show({ message: 'Consolidated' });
      onDone();
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not consolidate') }),
  });

  return (
    <Card withBorder>
      <Stack gap="xs">
        <Group gap="xs">
          <Text fw={600}>Homotypic conflict</Text>
          {cluster.hasExceptions && (
            <Badge color="yellow" variant="light">pro parte / dual status — review</Badge>
          )}
        </Group>
        <Text size="xs" c="dimmed">Members</Text>
        {cluster.members.map((m) => (
          <Text key={m.id} size="sm">
            <span>{m.formattedName}</span>{' '}
            <Text span c="dimmed" size="xs">
              {m.status}
              {m.proParte ? ' · pro parte' : ''}
              {m.dualStatus ? ' · dual status' : ''}
            </Text>
          </Text>
        ))}
        <Text size="xs" c="dimmed" mt="xs">Keep as the single accepted name</Text>
        <Radio.Group value={String(survivor)} onChange={(v) => setSurvivor(Number(v))}>
          <Stack gap={4}>
            {cluster.accepted.map((a) => (
              <Radio
                key={a.id}
                value={String(a.id)}
                label={
                  <span>
                    <span>{a.formattedName}</span>{' '}
                    <Text span c="dimmed" size="xs">({a.descendantCount} descendants)</Text>
                  </span>
                }
              />
            ))}
          </Stack>
        </Radio.Group>
        <Group justify="flex-end">
          <Button variant="default" onClick={onDone}>Skip</Button>
          <Button loading={consolidate.isPending} onClick={() => consolidate.mutate()}>
            Consolidate
          </Button>
        </Group>
      </Stack>
    </Card>
  );
}

// Full page: scans a focal taxon's accepted subtree for homotypic clusters resolving to >1 accepted
// name, and resolves each. Launched from a taxon's action menu.
export default function HomotypicConflictsPage() {
  const { projectId, rootId } = useParams();
  const pid = Number(projectId);
  const root = Number(rootId);
  const { data, isLoading } = useQuery({
    queryKey: ['homotypicConflicts', pid, root],
    queryFn: () => getHomotypicConflicts(pid, root),
  });
  // locally-resolved/skipped clusters are hidden without a re-fetch
  const [dismissed, setDismissed] = useState<Set<string>>(new Set());

  if (isLoading) return <Loader />;
  const clusters = (data ?? []).filter((c) => !dismissed.has(clusterKey(c)));

  return (
    <Stack>
      <Title order={3}>Homotypic conflicts</Title>
      {clusters.length === 0 ? (
        <Text c="dimmed">No homotypic conflicts found in this subtree.</Text>
      ) : (
        clusters.map((c) => (
          <ConflictCard
            key={clusterKey(c)}
            pid={pid}
            cluster={c}
            onDone={() => setDismissed((prev) => new Set(prev).add(clusterKey(c)))}
          />
        ))
      )}
    </Stack>
  );
}
