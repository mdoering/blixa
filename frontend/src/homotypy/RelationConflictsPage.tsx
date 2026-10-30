import { Badge, Button, Card, Checkbox, Group, Loader, Modal, Stack, Text, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { ApiError, messageFor } from '../api/client';
import { getProject } from '../api/projects';
import {
  cleanupRelations,
  getRelationConflicts,
  type JudgedRelation,
  type RelationConflictPair,
} from '../api/relationCleanup';
import HelpLink from '../components/HelpLink';

const ISSUE_LABELS: Record<string, string> = {
  NAME_RELATION_CONFLICT: 'Contradicts another relation of these names',
  NAME_RELATION_REDUNDANT: 'Repeats what another relation says',
  SPELLING_CORRECTION_IMPLAUSIBLE: 'The names differ in more than their spelling',
  DUPLICATE: 'Exact duplicate',
};

// Types come back as stored (e.g. BASIONYM, spelling-correction): lowercase, _/- as space
const typeLabel = (t: string | null) => (t ?? '').trim().toLowerCase().replace(/[_-]/g, ' ');

function RelationRow({
  rel,
  pair,
  checked,
  canEdit,
  onToggle,
}: {
  rel: JudgedRelation;
  pair: RelationConflictPair;
  checked: boolean;
  canEdit: boolean;
  onToggle: () => void;
}) {
  const nameOf = (id: number | null) =>
    id === pair.n1.id ? pair.n1.label : id === pair.n2.id ? pair.n2.label : `#${id}`;
  const judged = rel.issues.length > 0 || rel.remove || rel.duplicate;
  const extras = [
    rel.referenceId != null ? `reference #${rel.referenceId}` : null,
    rel.page ? `p. ${rel.page}` : null,
    rel.remarks,
  ].filter(Boolean);
  return (
    <Group gap="xs" wrap="nowrap" align="flex-start">
      {canEdit && judged ? (
        <Checkbox checked={checked} onChange={onToggle} aria-label={`Delete relation ${rel.id}`} mt={2} />
      ) : (
        canEdit && <div style={{ width: 20 }} />
      )}
      <Stack gap={2}>
        <Text size="sm" c={judged ? undefined : 'dimmed'}>
          <Text span fw={600}>{typeLabel(rel.type)}</Text>: {nameOf(rel.usageId)} → {nameOf(rel.relatedUsageId)}
          {extras.length > 0 && (
            <Text span c="dimmed" size="xs"> · {extras.join(' · ')}</Text>
          )}
        </Text>
        {rel.issues.length > 0 && (
          <Group gap={4}>
            {rel.issues.map((i) => (
              <Badge key={i} color={rel.remove ? 'red' : 'yellow'} variant="light">
                {ISSUE_LABELS[i] ?? i}
              </Badge>
            ))}
          </Group>
        )}
      </Stack>
    </Group>
  );
}

// Full page: lists name pairs whose relations contradict, repeat or duplicate each other. Nothing is
// deleted unless the editor ticks it. Launched from a taxon's action menu (subtree) or the project
// settings (whole project).
export default function RelationConflictsPage() {
  const { projectId, rootId } = useParams();
  const pid = Number(projectId);
  const root = rootId != null ? Number(rootId) : undefined;
  const qc = useQueryClient();
  const { data: project } = useQuery({ queryKey: ['project', pid], queryFn: () => getProject(pid) });
  const canEdit = project ? ['owner', 'editor'].includes(project.role) : false;
  const { data, isLoading, isError } = useQuery({
    queryKey: ['relationConflicts', pid, root ?? null],
    queryFn: () => getRelationConflicts(pid, root),
  });
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [confirming, setConfirming] = useState(false);

  // every (re)load restarts from the server's preselection
  useEffect(() => {
    if (!data) return;
    setSelected(new Set(data.flatMap((p) => p.relations).filter((r) => r.preselected).map((r) => r.id)));
  }, [data]);

  const refresh = () => qc.invalidateQueries({ queryKey: ['relationConflicts', pid] });
  const del = useMutation({
    mutationFn: () => {
      const all = (data ?? []).flatMap((p) => p.relations);
      return cleanupRelations(
        pid,
        all.filter((r) => selected.has(r.id)).map((r) => ({ id: r.id, version: r.version })),
      );
    },
    onSuccess: async (r) => {
      setConfirming(false);
      notifications.show({ message: `Deleted ${r.deleted} name relations` });
      await Promise.all(
        ['usageRelations', 'usage', 'synonymy', 'issues'].map((key) =>
          qc.invalidateQueries({ queryKey: [key, pid] })),
      );
      await refresh();
    },
    onError: async (e) => {
      setConfirming(false);
      if (e instanceof ApiError && e.status === 409) {
        notifications.show({
          color: 'orange',
          message: 'Changed by someone else — nothing was deleted, refreshing',
        });
      } else {
        notifications.show({ color: 'red', message: messageFor(e, 'Could not delete the relations') });
      }
      await refresh();
    },
  });

  if (isLoading) return <Loader />;
  if (isError) return <Text c="red">Could not load name relations.</Text>;
  const pairs = data ?? [];
  const toggle = (id: number) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (!next.delete(id)) next.add(id);
      return next;
    });

  return (
    <Stack>
      <Group justify="space-between">
        <Group gap={4} wrap="nowrap">
          <Title order={3}>Clean name relations</Title>
          <HelpLink page="relation-cleanup" label="Help: clean name relations" />
        </Group>
        {canEdit && pairs.length > 0 && (
          <Button color="red" disabled={selected.size === 0} onClick={() => setConfirming(true)}>
            Delete selected ({selected.size})
          </Button>
        )}
      </Group>
      {pairs.length === 0 ? (
        <Text c="dimmed">No conflicting name relations</Text>
      ) : (
        pairs.map((p) => (
          <Card key={`${p.n1.id}-${p.n2.id}`} withBorder>
            <Stack gap="xs">
              <Text fw={600}>
                {p.n1.label} <Text span c="dimmed" size="xs">{p.n1.status}</Text>
                {' · '}
                {p.n2.label} <Text span c="dimmed" size="xs">{p.n2.status}</Text>
              </Text>
              {p.relations.map((r) => (
                <RelationRow
                  key={r.id}
                  rel={r}
                  pair={p}
                  checked={selected.has(r.id)}
                  canEdit={canEdit}
                  onToggle={() => toggle(r.id)}
                />
              ))}
            </Stack>
          </Card>
        ))
      )}
      <Modal opened={confirming} onClose={() => setConfirming(false)} title="Delete name relations">
        <Stack>
          <Text>
            Delete {selected.size} name relations? Only the ticked ones are deleted; this is recorded
            in the history.
          </Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setConfirming(false)}>Cancel</Button>
            <Button color="red" loading={del.isPending} onClick={() => del.mutate()}>Delete</Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}
