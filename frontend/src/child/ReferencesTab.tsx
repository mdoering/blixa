import { Anchor, Badge, Button, Group, Stack, Table, Text, TextInput } from '@mantine/core';
import { modals } from '@mantine/modals';
import { notifications } from '@mantine/notifications';
import { IconExternalLink, IconPlus, IconX } from '@tabler/icons-react';
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, messageFor } from '../api/client';
import { listReferences } from '../api/references';
import { addWebReference, setUsageReferences } from '../api/usages';
import type { Reference } from '../api/types';
import EntitySelect from './EntitySelect';
import { referenceOptions } from './NameRelationsTab';

export interface ReferencesTabProps {
  pid: number;
  usageId: number;
  // The loaded usage's reference_id[] and version -- passed down by TaxonDetail (which already
  // holds the usage) rather than re-fetched here, so this tab always mutates against the
  // currently-loaded optimistic-lock version.
  referenceIds: number[];
  version: number;
  canEdit: boolean;
}

// Resolves the usage's reference_id[] against the project's references (GET /references) so each
// row can show a real citation/title rather than a bare id -- a project reference list beyond the
// picker's page size falls back to '#<id>', matching EntitySelect's own "current" fallback.
export default function ReferencesTab({
  pid,
  usageId,
  referenceIds,
  version,
  canEdit,
}: ReferencesTabProps) {
  const queryClient = useQueryClient();
  const [pickedId, setPickedId] = useState<string | null>(null);
  const [url, setUrl] = useState('');

  const { data } = useQuery({
    queryKey: ['references', pid],
    queryFn: () => listReferences(pid, { limit: 200, offset: 0 }),
  });
  const byId = new Map((data ?? []).map((r) => [r.id, r]));

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['usage', pid, usageId] });

  const setRefs = useMutation({
    mutationFn: (ids: number[]) => setUsageReferences(pid, usageId, ids, version),
    onSuccess: async () => {
      await invalidate();
      notifications.show({ message: 'Saved' });
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 409) {
        notifications.show({ color: 'orange', message: 'Changed by someone else — reloading' });
        invalidate();
        return;
      }
      notifications.show({ color: 'red', message: messageFor(e, 'Could not save') });
    },
  });

  const addWeb = useMutation({
    mutationFn: (u: string) => addWebReference(pid, usageId, u),
    onSuccess: async () => {
      await invalidate();
      setUrl('');
      notifications.show({ message: 'Added' });
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not add') }),
  });

  const addExisting = () => {
    if (!pickedId) return;
    const id = Number(pickedId);
    setPickedId(null);
    if (referenceIds.includes(id)) return;
    setRefs.mutate([...referenceIds, id]);
  };

  const remove = (id: number) =>
    modals.openConfirmModal({
      title: 'Remove this reference?',
      children: 'This removes the reference from this taxon — the reference itself is not deleted.',
      labels: { confirm: 'Remove', cancel: 'Cancel' },
      confirmProps: { color: 'red' },
      onConfirm: () => setRefs.mutate(referenceIds.filter((i) => i !== id)),
    });

  return (
    <Stack gap="sm">
      {referenceIds.length === 0 ? (
        <Text size="sm" c="dimmed">
          None
        </Text>
      ) : (
        <Table>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Reference</Table.Th>
              {canEdit && <Table.Th />}
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {referenceIds.map((id) => {
              const r = byId.get(id);
              return (
                <Table.Tr key={id}>
                  <Table.Td>
                    <ReferenceCell reference={r} id={id} />
                  </Table.Td>
                  {canEdit && (
                    <Table.Td>
                      <Button
                        size="xs"
                        variant="subtle"
                        color="red"
                        leftSection={<IconX size={14} />}
                        onClick={() => remove(id)}
                      >
                        Remove
                      </Button>
                    </Table.Td>
                  )}
                </Table.Tr>
              );
            })}
          </Table.Tbody>
        </Table>
      )}

      {canEdit && (
        <Stack gap="sm">
          <Group align="flex-end">
            <div style={{ flex: 1 }}>
              <EntitySelect
                label="Add existing reference"
                value={pickedId}
                onChange={setPickedId}
                load={referenceOptions(pid)}
                queryKey={['refOptions', pid]}
              />
            </div>
            <Button leftSection={<IconPlus size={14} />} onClick={addExisting} disabled={!pickedId}>
              Add
            </Button>
          </Group>
          <Group align="flex-end">
            <TextInput
              label="Add web URL"
              placeholder="https://…"
              value={url}
              onChange={(e) => setUrl(e.currentTarget.value)}
              style={{ flex: 1 }}
            />
            <Button
              leftSection={<IconPlus size={14} />}
              onClick={() => addWeb.mutate(url)}
              disabled={!url.trim()}
              loading={addWeb.isPending}
            >
              Add
            </Button>
          </Group>
        </Stack>
      )}
    </Stack>
  );
}

function ReferenceCell({ reference: r, id }: { reference: Reference | undefined; id: number }) {
  if (!r) {
    return (
      <Text size="sm" c="dimmed">
        #{id}
      </Text>
    );
  }
  if (r.type === 'webpage') {
    return (
      <Group gap="xs" wrap="nowrap">
        <Badge size="sm" variant="light">
          web
        </Badge>
        {r.link ? (
          <Anchor
            size="sm"
            href={r.link}
            target="_blank"
            rel="noreferrer"
            style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}
          >
            {r.title ?? r.link}
            <IconExternalLink size={12} />
          </Anchor>
        ) : (
          <Text size="sm">{r.title ?? `#${r.id}`}</Text>
        )}
      </Group>
    );
  }
  return <Text size="sm">{r.citation ?? r.title ?? `#${r.id}`}</Text>;
}
