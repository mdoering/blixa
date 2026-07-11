import {
  ActionIcon,
  Anchor,
  Badge,
  Button,
  Group,
  Menu,
  Select,
  Stack,
  Table,
  Text,
  Title,
} from '@mantine/core';
import { IconDotsVertical, IconRefresh } from '@tabler/icons-react';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { notifications } from '@mantine/notifications';
import { messageFor } from '../api/client';
import { getProject } from '../api/projects';
import { issueSummary, listIssues, reviewIssue, revalidate } from '../api/issues';

const PAGE = 25;
const SEVERITY_COLOR: Record<string, string> = { error: 'red', warning: 'yellow', info: 'blue' };
const STATUS_COLOR: Record<string, string> = {
  open: 'orange',
  accepted: 'red',
  rejected: 'gray',
  done: 'green',
};
const STATUS_OPTIONS = ['open', 'accepted', 'rejected', 'done'].map((v) => ({ value: v, label: v }));
const SEVERITY_OPTIONS = ['error', 'warning', 'info'].map((v) => ({ value: v, label: v }));

// Project-level validation dashboard: a severity/status rollup + Revalidate, over a filterable,
// paged issue table with per-row Accept/Reject/Reopen (owner/editor).
export default function IssuesPage() {
  const { projectId } = useParams();
  const pid = Number(projectId);
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<string | null>('open');
  const [severity, setSeverity] = useState<string | null>(null);
  const [page, setPage] = useState(0);

  const { data: project } = useQuery({ queryKey: ['project', pid], queryFn: () => getProject(pid) });
  const canEdit = project ? ['owner', 'editor'].includes(project.role) : false;

  const { data: summary } = useQuery({
    queryKey: ['issueSummary', pid],
    queryFn: () => issueSummary(pid),
  });
  const { data: issues } = useQuery({
    queryKey: ['issues', pid, status, severity, page],
    queryFn: () =>
      listIssues(pid, {
        status: status ?? undefined,
        severity: severity ?? undefined,
        limit: PAGE,
        offset: page * PAGE,
      }),
  });

  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: ['issues', pid] });
    await queryClient.invalidateQueries({ queryKey: ['issueSummary', pid] });
  };
  const revalidateMut = useMutation({
    mutationFn: () => revalidate(pid),
    onSuccess: async () => {
      await refresh();
      notifications.show({ message: 'Revalidated' });
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Revalidate failed') }),
  });
  const reviewMut = useMutation({
    mutationFn: ({ id, action }: { id: number; action: string }) => reviewIssue(pid, id, action),
    onSuccess: refresh,
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Review failed') }),
  });

  const rows = issues ?? [];

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={3} m={0}>
          Issues
        </Title>
        {canEdit && (
          <Button
            leftSection={<IconRefresh size={14} />}
            variant="default"
            loading={revalidateMut.isPending}
            onClick={() => revalidateMut.mutate()}
          >
            Revalidate
          </Button>
        )}
      </Group>

      {summary && (
        <Group gap="xs">
          <Badge variant="light">total {summary.total}</Badge>
          {SEVERITY_OPTIONS.map((s) =>
            (summary.bySeverity[s.value] ?? 0) > 0 ? (
              <Badge key={s.value} color={SEVERITY_COLOR[s.value]} variant="light">
                {s.value} {summary.bySeverity[s.value]}
              </Badge>
            ) : null,
          )}
          {STATUS_OPTIONS.map((s) =>
            (summary.byStatus[s.value] ?? 0) > 0 ? (
              <Badge key={s.value} color={STATUS_COLOR[s.value]} variant="outline">
                {s.value} {summary.byStatus[s.value]}
              </Badge>
            ) : null,
          )}
        </Group>
      )}

      <Group>
        <Select
          placeholder="Any status"
          clearable
          data={STATUS_OPTIONS}
          value={status}
          onChange={(v) => {
            setStatus(v);
            setPage(0);
          }}
          w={160}
        />
        <Select
          placeholder="Any severity"
          clearable
          data={SEVERITY_OPTIONS}
          value={severity}
          onChange={(v) => {
            setSeverity(v);
            setPage(0);
          }}
          w={160}
        />
      </Group>

      <Table striped>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Rule</Table.Th>
            <Table.Th>Severity</Table.Th>
            <Table.Th>Message</Table.Th>
            <Table.Th>Entity</Table.Th>
            <Table.Th>Status</Table.Th>
            {canEdit && <Table.Th />}
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {rows.map((i) => (
            <Table.Tr key={i.id}>
              <Table.Td>
                <Text size="sm">{i.rule}</Text>
              </Table.Td>
              <Table.Td>
                <Badge size="sm" color={SEVERITY_COLOR[i.severity] ?? 'gray'}>
                  {i.severity}
                </Badge>
              </Table.Td>
              <Table.Td>
                <Text size="sm">{i.message}</Text>
              </Table.Td>
              <Table.Td>
                {i.entityType === 'name_usage' ? (
                  <Anchor
                    component={Link}
                    to={`/projects/${pid}/names?usage=${i.entityId}`}
                    size="sm"
                  >
                    {i.entityType} #{i.entityId}
                  </Anchor>
                ) : (
                  <Text size="sm" c="dimmed">
                    {i.entityType} #{i.entityId}
                  </Text>
                )}
              </Table.Td>
              <Table.Td>
                <Badge size="sm" variant="outline" color={STATUS_COLOR[i.status] ?? 'gray'}>
                  {i.status}
                </Badge>
              </Table.Td>
              {canEdit && (
                <Table.Td>
                  <Menu withinPortal position="bottom-end">
                    <Menu.Target>
                      <ActionIcon variant="subtle" color="gray" aria-label={`Review ${i.rule}`}>
                        <IconDotsVertical size={16} />
                      </ActionIcon>
                    </Menu.Target>
                    <Menu.Dropdown>
                      <Menu.Item onClick={() => reviewMut.mutate({ id: i.id, action: 'accept' })}>
                        Accept
                      </Menu.Item>
                      <Menu.Item onClick={() => reviewMut.mutate({ id: i.id, action: 'reject' })}>
                        Reject
                      </Menu.Item>
                      <Menu.Item onClick={() => reviewMut.mutate({ id: i.id, action: 'reopen' })}>
                        Reopen
                      </Menu.Item>
                    </Menu.Dropdown>
                  </Menu>
                </Table.Td>
              )}
            </Table.Tr>
          ))}
          {rows.length === 0 && (
            <Table.Tr>
              <Table.Td colSpan={6}>
                <Text c="dimmed" size="sm">
                  No issues
                </Text>
              </Table.Td>
            </Table.Tr>
          )}
        </Table.Tbody>
      </Table>

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
