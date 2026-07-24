import { Alert, Badge, Button, Checkbox, Group, Loader, Modal, Stack, Table, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { messageFor } from '../api/client';
import { getGbifTypes, type GbifTypeCandidate } from '../api/gbifTypes';
import { createTypeMaterial } from '../api/typeMaterial';

interface Props {
  pid: number;
  usageId: number;
  opened: boolean;
  onClose: () => void;
}

// "Import from GBIF" on the Types tab: resolve the name to COL, list its GBIF type specimens, and
// import the ticked ones as TypeMaterial. Already-imported occurrences (deduped by occurrenceID
// server-side) are shown disabled. Importing POSTs each ticked candidate to the normal type-material
// create endpoint, then invalidates the Types list so the new rows appear.
export default function GbifTypesModal({ pid, usageId, opened, onClose }: Props) {
  const queryClient = useQueryClient();
  const [selected, setSelected] = useState<Set<number>>(new Set());

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['gbifTypes', pid, usageId],
    queryFn: () => getGbifTypes(pid, usageId),
    enabled: opened,
  });

  const importMut = useMutation({
    mutationFn: async (chosen: GbifTypeCandidate[]) => {
      for (const c of chosen) {
        await createTypeMaterial(pid, usageId, {
          citation: c.citation ?? undefined,
          status: c.status ?? undefined,
          institutionCode: c.institutionCode ?? undefined,
          catalogNumber: c.catalogNumber ?? undefined,
          occurrenceId: c.occurrenceId ?? undefined,
          locality: c.locality ?? undefined,
          country: c.country ?? undefined,
          collector: c.collector ?? undefined,
          date: c.date ?? undefined,
          sex: c.sex ?? undefined,
          link: c.link ?? undefined,
          latitude: c.latitude ?? undefined,
          longitude: c.longitude ?? undefined,
        });
      }
      return chosen.length;
    },
    onSuccess: async (n) => {
      await queryClient.invalidateQueries({ queryKey: ['type material', pid, usageId] });
      notifications.show({ message: `Imported ${n} type specimen${n === 1 ? '' : 's'}` });
      setSelected(new Set());
      onClose();
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Import failed') }),
  });

  const candidates = data?.candidates ?? [];
  const toggle = (i: number) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(i)) next.delete(i);
      else next.add(i);
      return next;
    });
  const chosen = candidates.filter((_, i) => selected.has(i));

  return (
    <Modal opened={opened} onClose={onClose} size="xl" title="Import type specimens from GBIF">
      {isLoading && (
        <Group gap="xs">
          <Loader size="sm" />
          <Text>Searching GBIF…</Text>
        </Group>
      )}
      {isError && <Alert color="red">{messageFor(error, 'GBIF search failed')}</Alert>}
      {data && data.colId == null && (
        <Text c="dimmed">No COL match for this name — nothing to import from GBIF.</Text>
      )}
      {data && data.colId != null && candidates.length === 0 && (
        <Text c="dimmed">No type specimens found in GBIF for this taxon.</Text>
      )}
      {candidates.length > 0 && (
        <Stack>
          {data?.truncated && (
            <Text size="sm" c="dimmed">
              Showing the first {candidates.length} type specimens.
            </Text>
          )}
          <Table>
            <Table.Thead>
              <Table.Tr>
                <Table.Th />
                <Table.Th>Type</Table.Th>
                <Table.Th>Name</Table.Th>
                <Table.Th>Institution</Table.Th>
                <Table.Th>Country</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {candidates.map((c, i) => (
                <Table.Tr key={c.occurrenceId ?? i}>
                  <Table.Td>
                    <Checkbox
                      aria-label={`Select ${c.status ?? 'type'} ${c.catalogNumber ?? ''}`.trim()}
                      checked={selected.has(i)}
                      disabled={c.alreadyImported}
                      onChange={() => toggle(i)}
                    />
                  </Table.Td>
                  <Table.Td>
                    <Group gap={6} wrap="nowrap">
                      <Badge variant="light">{c.status ?? '—'}</Badge>
                      {c.alreadyImported && (
                        <Text span size="xs" c="dimmed">
                          imported
                        </Text>
                      )}
                    </Group>
                  </Table.Td>
                  <Table.Td>{c.citation ?? '—'}</Table.Td>
                  <Table.Td>{[c.institutionCode, c.catalogNumber].filter(Boolean).join(' ') || '—'}</Table.Td>
                  <Table.Td>{c.country ?? '—'}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>
              Cancel
            </Button>
            <Button
              disabled={chosen.length === 0}
              loading={importMut.isPending}
              onClick={() => importMut.mutate(chosen)}
            >
              Import{chosen.length > 0 ? ` ${chosen.length}` : ''}
            </Button>
          </Group>
        </Stack>
      )}
    </Modal>
  );
}
