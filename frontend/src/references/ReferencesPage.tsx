import {
  ActionIcon,
  Anchor,
  Button,
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
import { IconDotsVertical, IconFileImport, IconPlus, IconSearch, IconWorld } from '@tabler/icons-react';
import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { messageFor } from '../api/client';
import { getProject } from '../api/projects';
import { deleteReference, listReferences } from '../api/references';
import type { CreateRefPayload, Reference } from '../api/types';
import ImportBibtexModal from './ImportBibtexModal';
import ImportDoiModal from './ImportDoiModal';
import ImportRisModal from './ImportRisModal';
import ReferenceForm from './ReferenceForm';

const PAGE = 25;

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
  const [page, setPage] = useState(0);
  const [form, setForm] = useState<{ reference: Reference | null; initial?: CreateRefPayload } | null>(
    null,
  );
  const [importDoi, setImportDoi] = useState(false);
  const [importBib, setImportBib] = useState(false);
  const [importRis, setImportRis] = useState(false);

  useEffect(() => {
    setPage(0);
  }, [debouncedQ]);

  const params = { q: debouncedQ.trim() || undefined, limit: PAGE, offset: page * PAGE };
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
            <Button leftSection={<IconPlus size={14} />} onClick={() => setForm({ reference: null })}>
              New reference
            </Button>
          </Group>
        )}
      </Group>

      <TextInput
        placeholder="Search citations…"
        leftSection={<IconSearch size={14} />}
        value={q}
        onChange={(e) => setQ(e.currentTarget.value)}
        w={320}
      />

      <Table striped highlightOnHover>
        <Table.Thead>
          <Table.Tr>
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
              <Table.Td onClick={() => canEdit && setForm({ reference: r })}>
                <Text size="sm">{r.author ?? '—'}</Text>
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
              <Table.Td colSpan={6}>
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
    </Stack>
  );
}
