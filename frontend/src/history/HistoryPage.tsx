import { Anchor, Badge, Button, Code, Group, Paper, Select, Spoiler, Stack, Text, Title } from '@mantine/core';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { listChanges, listTasks } from '../api/changes';
import type { Change } from '../api/types';

dayjs.extend(relativeTime);

const PAGE = 25;
const OP_COLOR: Record<string, string> = { CREATE: 'green', UPDATE: 'blue', DELETE: 'red' };

function prettyDiff(diff: string): string {
  try {
    return JSON.stringify(JSON.parse(diff), null, 2);
  } catch {
    return diff;
  }
}

// Where a change's entity still lives, if it's still linkable: only for entity types with their
// own detail page, and only while the entity still exists (a DELETE means it's gone).
function entityLink(change: Change, pid: number): string | null {
  if (change.operation === 'DELETE') return null;
  if (change.entityType === 'name_usage') return `/projects/${pid}/names?usage=${change.entityId}`;
  if (change.entityType === 'reference') return `/projects/${pid}/references?ref=${change.entityId}`;
  return null;
}

function ChangeRow({ change, pid }: { change: Change; pid: number }) {
  const label = `${change.entityType} #${change.entityId}`;
  const to = entityLink(change, pid);
  return (
    <Paper withBorder p="sm">
      <Group justify="space-between" wrap="nowrap">
        <Group gap="sm" wrap="nowrap">
          <Badge size="sm" color={OP_COLOR[change.operation] ?? 'gray'}>
            {change.operation.toLowerCase()}
          </Badge>
          {to ? (
            <Anchor component={Link} to={to} size="sm" fw={500}>
              {label}
            </Anchor>
          ) : (
            <Text size="sm" fw={500}>
              {label}
            </Text>
          )}
        </Group>
        <Group gap="xs" wrap="nowrap">
          <Text size="sm" c="dimmed">
            {change.username ?? 'unknown'}
          </Text>
          <Text size="xs" c="dimmed" title={change.at}>
            {dayjs(change.at).fromNow()}
          </Text>
        </Group>
      </Group>
      <Spoiler maxHeight={0} showLabel="Show diff" hideLabel="Hide diff">
        <Code block mt="xs">
          {prettyDiff(change.diff)}
        </Code>
      </Spoiler>
    </Paper>
  );
}

// Project-level audit log (changelog): reverse-chronological changes with a collapsible JSON diff,
// filterable by task. Read-only; any project member may view.
export default function HistoryPage() {
  const { projectId } = useParams();
  const pid = Number(projectId);
  const [taskId, setTaskId] = useState<string | null>(null);
  const [page, setPage] = useState(0);

  const { data: tasks } = useQuery({ queryKey: ['tasks', pid], queryFn: () => listTasks(pid) });
  const { data: changes } = useQuery({
    queryKey: ['changes', pid, taskId, page],
    queryFn: () =>
      listChanges(pid, {
        taskId: taskId ? Number(taskId) : undefined,
        limit: PAGE,
        offset: page * PAGE,
      }),
  });

  const rows = changes ?? [];

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={3} m={0}>
          History
        </Title>
        <Select
          placeholder="All tasks"
          clearable
          w={220}
          data={(tasks ?? []).map((t) => ({ value: String(t.id), label: t.title }))}
          value={taskId}
          onChange={(v) => {
            setTaskId(v);
            setPage(0);
          }}
        />
      </Group>

      {rows.length === 0 ? (
        <Text c="dimmed" size="sm">
          No changes
        </Text>
      ) : (
        <Stack gap="xs">
          {rows.map((c) => (
            <ChangeRow key={c.id} change={c} pid={pid} />
          ))}
        </Stack>
      )}

      <Group justify="flex-end">
        <Button variant="default" size="xs" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
          Previous
        </Button>
        <Text size="sm" c="dimmed">
          Page {page + 1}
        </Text>
        <Button
          variant="default"
          size="xs"
          disabled={rows.length < PAGE}
          onClick={() => setPage((p) => p + 1)}
        >
          Next
        </Button>
      </Group>
    </Stack>
  );
}
