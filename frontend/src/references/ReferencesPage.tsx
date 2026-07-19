import {
  ActionIcon,
  Anchor,
  Button,
  Checkbox,
  Group,
  Menu,
  Stack,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { modals } from '@mantine/modals';
import { useDebouncedValue } from '@mantine/hooks';
import { notifications } from '@mantine/notifications';
import {
  IconDotsVertical,
  IconFileImport,
  IconPlus,
  IconSearch,
  IconWand,
  IconWorld,
} from '@tabler/icons-react';
import { useEffect, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { messageFor } from '../api/client';
import MergeRecordsModal from '../merge/MergeRecordsModal';
import { getProject } from '../api/projects';
import { deleteReference, getReference, listReferences } from '../api/references';
import type { CreateRefPayload, CslName, Reference } from '../api/types';
import ImportBibtexModal from './ImportBibtexModal';
import ImportDoiModal from './ImportDoiModal';
import ImportRisModal from './ImportRisModal';
import ReconcileJournalsModal from './ReconcileJournalsModal';
import ReferenceForm from './ReferenceForm';
import { parseYearFilter } from './parseYearFilter';

const PAGE = 25;

// Renders a CslName[] author/editor as a compact display string: an institutional name shows its
// literal, a personal name shows "Family, Given" -- joined with "; " for multiple names. Mirrors
// CslName.toString() on the backend closely enough for a table cell (full particle/suffix
// formatting is CslNameEditor's/Task 6's concern, not this read-only list view's).
function authorsToString(names: CslName[] | null): string {
  if (!names || names.length === 0) return '';
  return names
    .map((n) => n.literal || [n.family, n.given].filter(Boolean).join(', '))
    .join('; ');
}

// Project References editor: fuzzy citation search, a paged table, create/edit/delete, and DOI +
// BibTeX + RIS import.
export default function ReferencesPage() {
  const { projectId } = useParams();
  const pid = Number(projectId);
  const queryClient = useQueryClient();

  const { data: project } = useQuery({ queryKey: ['project', pid], queryFn: () => getProject(pid) });
  const canEdit = project ? ['owner', 'editor'].includes(project.role) : false;

  const [q, setQ] = useState('');
  const [debouncedQ] = useDebouncedValue(q, 300);
  const [year, setYear] = useState('');
  const [debouncedYear] = useDebouncedValue(year, 300);
  const [page, setPage] = useState(0);
  const [form, setForm] = useState<{ reference: Reference | null; initial?: CreateRefPayload } | null>(
    null,
  );

  // Deep-link support: ?ref=<id> (e.g. from the History page) opens that reference's edit form,
  // even if it isn't on the current filtered/paginated table page. Mirrors NameSearchPage's
  // ?usage= handling. If the reference no longer exists (deleted), the fetch 404s and the query
  // simply stays without data -- no form opens, no crash.
  const [searchParams] = useSearchParams();
  const refParam = searchParams.get('ref');
  const { data: linkedReference } = useQuery({
    queryKey: ['reference', pid, refParam],
    queryFn: () => getReference(pid, Number(refParam)),
    enabled: !!refParam,
    retry: false,
  });
  useEffect(() => {
    if (linkedReference) setForm({ reference: linkedReference });
  }, [linkedReference]);

  const [importDoi, setImportDoi] = useState(false);
  const [importBib, setImportBib] = useState(false);
  const [importRis, setImportRis] = useState(false);
  const [reconcileOpen, setReconcileOpen] = useState(false);

  // Multi-select for the "Merge N selected…" action (reference dedupe, reuses Task 3's
  // MergeRecordsModal with entity="reference").
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [mergeOpen, setMergeOpen] = useState(false);
  const toggleSelected = (id: number) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  useEffect(() => {
    setPage(0);
  }, [debouncedQ, debouncedYear]);

  const params = {
    q: debouncedQ.trim() || undefined,
    ...parseYearFilter(debouncedYear),
    limit: PAGE,
    offset: page * PAGE,
  };
  const { data: refs } = useQuery({
    queryKey: ['references', pid, params],
    queryFn: () => listReferences(pid, params),
    placeholderData: keepPreviousData,
  });
  const rows = refs ?? [];

  const del = useMutation({
    mutationFn: (id: number) => deleteReference(pid, id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['references', pid] });
      notifications.show({ message: 'Deleted' });
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not delete') }),
  });

  const confirmDelete = (r: Reference) =>
    modals.openConfirmModal({
      title: 'Delete this reference?',
      children: `This permanently deletes "${r.title ?? r.citation ?? `reference #${r.id}`}".`,
      labels: { confirm: 'Delete', cancel: 'Cancel' },
      confirmProps: { color: 'red' },
      onConfirm: () => del.mutate(r.id),
    });

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={3} m={0}>
          References
        </Title>
        {canEdit && (
          <Group gap="xs">
            <Button
              variant="default"
              leftSection={<IconWorld size={14} />}
              onClick={() => setImportDoi(true)}
            >
              Import DOI
            </Button>
            <Button
              variant="default"
              leftSection={<IconFileImport size={14} />}
              onClick={() => setImportBib(true)}
            >
              Import BibTeX
            </Button>
            <Button
              variant="default"
              leftSection={<IconFileImport size={14} />}
              onClick={() => setImportRis(true)}
            >
              Import RIS
            </Button>
            <Button
              variant="default"
              leftSection={<IconWand size={14} />}
              onClick={() => setReconcileOpen(true)}
            >
              Reconcile journals…
            </Button>
            <Button leftSection={<IconPlus size={14} />} onClick={() => setForm({ reference: null })}>
              New reference
            </Button>
          </Group>
        )}
      </Group>

      <Group gap="xs">
        <TextInput
          placeholder="Search citations…"
          leftSection={<IconSearch size={14} />}
          value={q}
          onChange={(e) => setQ(e.currentTarget.value)}
          w={320}
        />
        <TextInput
          aria-label="Year"
          placeholder="Year e.g. 1941 or 1941-1944"
          value={year}
          onChange={(e) => setYear(e.currentTarget.value)}
          w={200}
        />
      </Group>

      {selected.size >= 2 && canEdit && (
        <Group>
          <Button variant="light" size="xs" onClick={() => setMergeOpen(true)}>
            Merge {selected.size} selected…
          </Button>
        </Group>
      )}

      <Table striped highlightOnHover>
        <Table.Thead>
          <Table.Tr>
            {canEdit && <Table.Th />}
            <Table.Th>Author</Table.Th>
            <Table.Th>Year</Table.Th>
            <Table.Th>Title</Table.Th>
            <Table.Th>Container</Table.Th>
            <Table.Th>DOI</Table.Th>
            {canEdit && <Table.Th />}
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {rows.map((r) => (
            <Table.Tr key={r.id} style={{ cursor: canEdit ? 'pointer' : 'default' }}>
              {canEdit && (
                <Table.Td onClick={(e) => e.stopPropagation()}>
                  <Checkbox
                    aria-label={`Select ${r.title ?? r.citation ?? `reference #${r.id}`}`}
                    checked={selected.has(r.id)}
                    onChange={() => toggleSelected(r.id)}
                  />
                </Table.Td>
              )}
              <Table.Td onClick={() => canEdit && setForm({ reference: r })}>
                <Text size="sm">{authorsToString(r.author) || '—'}</Text>
              </Table.Td>
              <Table.Td onClick={() => canEdit && setForm({ reference: r })}>
                <Text size="sm">{r.issued ?? '—'}</Text>
              </Table.Td>
              <Table.Td onClick={() => canEdit && setForm({ reference: r })}>
                <Text size="sm">{r.title ?? r.citation ?? '—'}</Text>
              </Table.Td>
              <Table.Td onClick={() => canEdit && setForm({ reference: r })}>
                <Text size="sm" c="dimmed">
                  {r.containerTitle ?? '—'}
                </Text>
              </Table.Td>
              <Table.Td>
                {r.doi ? (
                  <Anchor size="sm" href={`https://doi.org/${r.doi}`} target="_blank" rel="noreferrer">
                    {r.doi}
                  </Anchor>
                ) : (
                  <Text size="sm" c="dimmed">
                    —
                  </Text>
                )}
              </Table.Td>
              {canEdit && (
                <Table.Td>
                  <Menu withinPortal position="bottom-end">
                    <Menu.Target>
                      <ActionIcon variant="subtle" color="gray" aria-label={`Actions for ${r.title ?? r.id}`}>
                        <IconDotsVertical size={16} />
                      </ActionIcon>
                    </Menu.Target>
                    <Menu.Dropdown>
                      <Menu.Item onClick={() => setForm({ reference: r })}>Edit</Menu.Item>
                      <Menu.Item color="red" onClick={() => confirmDelete(r)}>
                        Delete
                      </Menu.Item>
                    </Menu.Dropdown>
                  </Menu>
                </Table.Td>
              )}
            </Table.Tr>
          ))}
          {rows.length === 0 && (
            <Table.Tr>
              <Table.Td colSpan={canEdit ? 7 : 5}>
                <Text c="dimmed" size="sm">
                  No references
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

      {form && (
        <ReferenceForm
          pid={pid}
          reference={form.reference}
          initial={form.initial}
          opened
          onClose={() => setForm(null)}
        />
      )}
      <ImportDoiModal
        pid={pid}
        opened={importDoi}
        onClose={() => setImportDoi(false)}
        onResolved={(payload) => setForm({ reference: null, initial: payload })}
      />
      <ImportBibtexModal pid={pid} opened={importBib} onClose={() => setImportBib(false)} />
      <ImportRisModal pid={pid} opened={importRis} onClose={() => setImportRis(false)} />
      <ReconcileJournalsModal pid={pid} opened={reconcileOpen} onClose={() => setReconcileOpen(false)} />
      <MergeRecordsModal
        entity="reference"
        pid={pid}
        ids={[...selected]}
        opened={mergeOpen}
        onClose={() => setMergeOpen(false)}
        onDone={() => {
          setSelected(new Set());
          queryClient.invalidateQueries({ queryKey: ['references', pid] });
        }}
      />
    </Stack>
  );
}
