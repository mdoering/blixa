import { Badge, Box, Button, Group, Pagination, Select, Table, Text, TextInput, Title } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconPlus, IconSearch } from '@tabler/icons-react';
import { useEffect, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { getProject, listMembers } from '../api/projects';
import { useMe } from '../auth/useMe';
import { listDiscussions, type Discussion, type DiscussionStatus } from '../api/discussions';
import DiscussionForm from './DiscussionForm';

const PAGE = 25;
const STATUS_COLOR: Record<DiscussionStatus, string> = {
  REVIEW: 'yellow',
  OPEN: 'blue',
  REJECTED: 'gray',
  RESOLVED: 'green',
};
const titleCase = (s: string) => s.charAt(0) + s.slice(1).toLowerCase();

// Project discussions: forum-style threads with full-text search, status/author filters, a
// created|modified sort, and paged results. Any member may start one; an editor or the author may
// edit/close/delete (see DiscussionForm).
export default function DiscussionsPage() {
  const { projectId } = useParams();
  const pid = Number(projectId);
  const { data: me } = useMe();
  const { data: project } = useQuery({ queryKey: ['project', pid], queryFn: () => getProject(pid) });
  const isEditor = project ? ['owner', 'editor'].includes(project.role) : false;
  const { data: members } = useQuery({ queryKey: ['members', pid], queryFn: () => listMembers(pid) });

  const [q, setQ] = useState('');
  const [debouncedQ] = useDebouncedValue(q, 300);
  const [statusFilter, setStatusFilter] = useState<string | null>(null);
  const [authorFilter, setAuthorFilter] = useState<string | null>(null);
  const [sort, setSort] = useState<'created' | 'modified'>('created');
  const [order, setOrder] = useState<'asc' | 'desc'>('desc');
  const [page, setPage] = useState(0);
  const [form, setForm] = useState<{ discussion: Discussion | null } | null>(null);

  useEffect(() => setPage(0), [debouncedQ, statusFilter, authorFilter, sort, order]);

  const params = {
    q: debouncedQ.trim() || undefined,
    status: (statusFilter as DiscussionStatus) || undefined,
    authorId: authorFilter ? Number(authorFilter) : undefined,
    sort,
    order,
    limit: PAGE,
    offset: page * PAGE,
  };
  const { data } = useQuery({
    queryKey: ['discussions', pid, params],
    queryFn: () => listDiscussions(pid, params),
    placeholderData: keepPreviousData,
  });
  const rows = data?.items ?? [];
  const total = data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / PAGE));

  const canManage = (d: Discussion) => isEditor || (me != null && d.authorId === me.id);

  return (
    <Box>
      <Group justify="space-between" mb="md">
        <Title order={3} m={0}>
          Discussions
        </Title>
        <Button leftSection={<IconPlus size={14} />} onClick={() => setForm({ discussion: null })}>
          New discussion
        </Button>
      </Group>

      <Group mb="md">
        <TextInput
          placeholder="Search discussions…"
          leftSection={<IconSearch size={14} />}
          value={q}
          onChange={(e) => setQ(e.currentTarget.value)}
          w={280}
        />
        <Select
          aria-label="Status filter"
          placeholder="Status"
          clearable
          data={(['REVIEW', 'OPEN', 'REJECTED', 'RESOLVED'] as const).map((s) => ({
            value: s,
            label: titleCase(s),
          }))}
          value={statusFilter}
          onChange={setStatusFilter}
          w={140}
        />
        <Select
          aria-label="Author filter"
          placeholder="Author"
          clearable
          data={(members ?? []).map((m) => ({ value: String(m.userId), label: m.username }))}
          value={authorFilter}
          onChange={setAuthorFilter}
          w={180}
        />
        <Select
          aria-label="Sort by"
          data={[
            { value: 'created', label: 'Created' },
            { value: 'modified', label: 'Modified' },
          ]}
          value={sort}
          onChange={(v) => v && setSort(v as 'created' | 'modified')}
          allowDeselect={false}
          w={130}
        />
        <Select
          aria-label="Order"
          data={[
            { value: 'desc', label: 'Newest' },
            { value: 'asc', label: 'Oldest' },
          ]}
          value={order}
          onChange={(v) => v && setOrder(v as 'asc' | 'desc')}
          allowDeselect={false}
          w={120}
        />
      </Group>

      <Table striped highlightOnHover>
        <Table.Thead>
          <Table.Tr>
            <Table.Th w={50}>#</Table.Th>
            <Table.Th>Title</Table.Th>
            <Table.Th>Status</Table.Th>
            <Table.Th>Author</Table.Th>
            <Table.Th>Updated</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {rows.map((d) => (
            <Table.Tr key={d.id} style={{ cursor: 'pointer' }} onClick={() => setForm({ discussion: d })}>
              <Table.Td>{d.id}</Table.Td>
              <Table.Td>{d.title}</Table.Td>
              <Table.Td>
                <Badge color={STATUS_COLOR[d.status]} variant="light">
                  {titleCase(d.status)}
                </Badge>
              </Table.Td>
              <Table.Td>{d.authorName ?? d.authorOrcid ?? '—'}</Table.Td>
              <Table.Td>{new Date(d.updatedAt).toLocaleDateString()}</Table.Td>
            </Table.Tr>
          ))}
          {rows.length === 0 && (
            <Table.Tr>
              <Table.Td colSpan={5}>
                <Text c="dimmed" ta="center" py="md">
                  No discussions
                </Text>
              </Table.Td>
            </Table.Tr>
          )}
        </Table.Tbody>
      </Table>

      {pageCount > 1 && (
        <Group justify="space-between" mt="md">
          <Text size="sm" c="dimmed">
            {total} discussion{total === 1 ? '' : 's'}
          </Text>
          <Pagination value={page + 1} onChange={(p) => setPage(p - 1)} total={pageCount} />
        </Group>
      )}

      {form && (
        <DiscussionForm
          pid={pid}
          discussion={form.discussion}
          opened
          canManage={form.discussion ? canManage(form.discussion) : true}
          onClose={() => setForm(null)}
        />
      )}
    </Box>
  );
}
