import {
  ActionIcon,
  Button,
  Checkbox,
  Group,
  Modal,
  Radio,
  Stack,
  Table,
  Text,
  TextInput,
} from '@mantine/core';
import { IconTrash } from '@tabler/icons-react';
import { notifications } from '@mantine/notifications';
import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { messageFor } from '../api/client';
import {
  definePropertyKey,
  deletePropertyKey,
  getPropertyKeys,
  mergePropertyKeys,
  type PropertyKeyInfo,
} from '../api/propertyKeys';

export interface PropertyKeysModalProps {
  pid: number;
  opened: boolean;
  onClose: () => void;
}

// Highest-count checked key wins; ties break on the key itself -- same deterministic default as
// ReconcileJournalsModal.defaultCanonical.
function defaultCanonical(selected: PropertyKeyInfo[]): string | null {
  if (selected.length === 0) return null;
  return [...selected].sort((a, b) => b.count - a.count || a.key.localeCompare(b.key))[0].key;
}

// Manage a project's standard taxon property keys: a table of every key (used ∪ defined) with its
// usage count and an editable description, an add-a-key form, per-key definition removal, and
// reconciliation (tick 2+ variant spellings, choose a canonical, rewrite property.property to it).
// The taxon Property tab's key field autocompletes from the same set. Mirrors ReconcileJournalsModal
// for journal names, with description editing added.
export default function PropertyKeysModal({ pid, opened, onClose }: PropertyKeysModalProps) {
  const queryClient = useQueryClient();
  const [checked, setChecked] = useState<Set<string>>(new Set());
  const [canonicalPick, setCanonicalPick] = useState<string | null>(null);
  const [customCanonical, setCustomCanonical] = useState('');
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [newKey, setNewKey] = useState('');
  const [newDesc, setNewDesc] = useState('');

  const { data: facet, isLoading } = useQuery({
    queryKey: ['propertyKeys', pid],
    queryFn: () => getPropertyKeys(pid),
    enabled: opened,
  });
  const rows = facet ?? [];

  // Reset all local state whenever the modal is (re)opened.
  useEffect(() => {
    if (opened) {
      setChecked(new Set());
      setCanonicalPick(null);
      setCustomCanonical('');
      setDrafts({});
      setNewKey('');
      setNewDesc('');
    }
  }, [opened]);

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['propertyKeys', pid] });
  };

  const defineMut = useMutation({
    mutationFn: ({ key, description }: { key: string; description: string }) =>
      definePropertyKey(pid, key, description.trim() || null),
    onSuccess: () => invalidate(),
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Save failed') }),
  });

  const deleteMut = useMutation({
    mutationFn: (key: string) => deletePropertyKey(pid, key),
    onSuccess: () => invalidate(),
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Remove failed') }),
  });

  const toggle = (key: string) =>
    setChecked((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  const selectedRows = rows.filter((r) => checked.has(r.key));

  // Re-default the canonical whenever the checked set changes, unless the pick is still valid --
  // same stickiness as ReconcileJournalsModal.
  useEffect(() => {
    if (canonicalPick != null && checked.has(canonicalPick)) return;
    setCanonicalPick(defaultCanonical(selectedRows));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [checked]);

  const canonical = customCanonical.trim() || canonicalPick || '';

  const mergeMut = useMutation({
    mutationFn: () => mergePropertyKeys(pid, canonical, [...checked]),
    onSuccess: (res) => {
      notifications.show({
        message: `Merged ${res.updated} propert${res.updated === 1 ? 'y' : 'ies'} into "${canonical}"`,
      });
      invalidate();
      // properties themselves changed key -- drop any cached taxon property lists too
      queryClient.invalidateQueries({ queryKey: ['property'] });
      setChecked(new Set());
      setCanonicalPick(null);
      setCustomCanonical('');
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Merge failed') }),
  });

  const canMerge = checked.size >= 2 && canonical.trim().length > 0;
  const addKey = () => {
    const key = newKey.trim();
    if (!key) return;
    defineMut.mutate(
      { key, description: newDesc },
      {
        onSuccess: () => {
          setNewKey('');
          setNewDesc('');
        },
      },
    );
  };

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      size="xl"
      title={<Text fw={600}>Property keys</Text>}
    >
      <Stack gap="md">
        {isLoading ? (
          <Text c="dimmed">Loading…</Text>
        ) : (
          <Table verticalSpacing="xs" horizontalSpacing="sm">
            <Table.Thead>
              <Table.Tr>
                <Table.Th w={32} />
                <Table.Th>Key</Table.Th>
                <Table.Th w={70}>Uses</Table.Th>
                <Table.Th>Description</Table.Th>
                <Table.Th w={140} />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.length === 0 ? (
                <Table.Tr>
                  <Table.Td colSpan={5}>
                    <Text c="dimmed" size="sm">
                      No property keys yet.
                    </Text>
                  </Table.Td>
                </Table.Tr>
              ) : (
                rows.map((r) => {
                  const draft = drafts[r.key] ?? r.description ?? '';
                  const dirty = draft !== (r.description ?? '');
                  const defined = r.description != null || r.count === 0;
                  return (
                    <Table.Tr key={r.key}>
                      <Table.Td>
                        <Checkbox
                          aria-label={r.key}
                          checked={checked.has(r.key)}
                          onChange={() => toggle(r.key)}
                        />
                      </Table.Td>
                      <Table.Td>
                        <Text size="sm">{r.key}</Text>
                      </Table.Td>
                      <Table.Td>
                        <Text size="sm" c={r.count === 0 ? 'dimmed' : undefined}>
                          {r.count}
                        </Text>
                      </Table.Td>
                      <Table.Td>
                        <TextInput
                          placeholder="Description"
                          value={draft}
                          onChange={(e) => {
                            // Capture the value synchronously: reading e.currentTarget inside the
                            // setState updater (which React runs later) would hit a nulled event.
                            const v = e.currentTarget.value;
                            setDrafts((prev) => ({ ...prev, [r.key]: v }));
                          }}
                        />
                      </Table.Td>
                      <Table.Td>
                        <Group gap="xs" justify="flex-end" wrap="nowrap">
                          <Button
                            size="compact-sm"
                            variant="light"
                            disabled={!dirty}
                            loading={defineMut.isPending && defineMut.variables?.key === r.key}
                            onClick={() => defineMut.mutate({ key: r.key, description: draft })}
                          >
                            Save
                          </Button>
                          {defined && (
                            <ActionIcon
                              variant="subtle"
                              color="red"
                              aria-label={`Remove definition of ${r.key}`}
                              loading={deleteMut.isPending && deleteMut.variables === r.key}
                              onClick={() => deleteMut.mutate(r.key)}
                            >
                              <IconTrash size={16} />
                            </ActionIcon>
                          )}
                        </Group>
                      </Table.Td>
                    </Table.Tr>
                  );
                })
              )}
            </Table.Tbody>
          </Table>
        )}

        <Group align="flex-end" gap="sm">
          <TextInput
            label="Add a standard key"
            placeholder="New key"
            value={newKey}
            onChange={(e) => setNewKey(e.currentTarget.value)}
            style={{ flex: 1 }}
          />
          <TextInput
            label=" "
            placeholder="Description (optional)"
            value={newDesc}
            onChange={(e) => setNewDesc(e.currentTarget.value)}
            style={{ flex: 2 }}
          />
          <Button variant="light" disabled={!newKey.trim()} loading={defineMut.isPending} onClick={addKey}>
            Add key
          </Button>
        </Group>

        {checked.size >= 2 && (
          <Stack gap="xs">
            <Radio.Group
              label="Merge into -- every checked key is rewritten to this one"
              value={canonicalPick}
              onChange={setCanonicalPick}
            >
              <Group gap="md" mt={4}>
                {selectedRows.map((r) => (
                  <Radio key={r.key} value={r.key} label={r.key} />
                ))}
              </Group>
            </Radio.Group>
            <TextInput
              label="Or type a custom canonical key"
              description="Overrides the selection above if non-empty"
              value={customCanonical}
              onChange={(e) => setCustomCanonical(e.currentTarget.value)}
            />
          </Stack>
        )}

        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            Close
          </Button>
          {checked.size >= 2 && (
            <Button
              loading={mergeMut.isPending}
              disabled={!canMerge}
              onClick={() => mergeMut.mutate()}
            >
              Merge {checked.size} keys into "{canonical || '…'}"
            </Button>
          )}
        </Group>
      </Stack>
    </Modal>
  );
}
