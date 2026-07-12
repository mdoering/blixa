import { Button, Checkbox, Group, Modal, Radio, Stack, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { messageFor } from '../api/client';
import {
  getContainerTitleFacet,
  mergeContainerTitle,
  type ContainerTitleFacet,
} from '../api/references';

export interface ReconcileJournalsModalProps {
  pid: number;
  opened: boolean;
  onClose: () => void;
}

// Highest-count checked value wins; ties break on the value itself -- deterministic default
// canonical pick, mirrors MergeRecordsModal's defaultSurvivorId.
function defaultCanonical(selected: ContainerTitleFacet[]): string | null {
  if (selected.length === 0) return null;
  return [...selected].sort((a, b) => b.count - a.count || a.value.localeCompare(b.value))[0].value;
}

// OpenRefine-style field reconciliation for reference.container_title: facet the distinct journal
// names in the project, let an editor tick 2+ variant spellings of the same journal, and normalize
// them to one canonical value (typed or picked from the checked set). This rewrites the
// container_title column across every matching reference -- it is NOT a record merge (see
// MergeRecordsModal for that).
export default function ReconcileJournalsModal({ pid, opened, onClose }: ReconcileJournalsModalProps) {
  const queryClient = useQueryClient();
  const [checked, setChecked] = useState<Set<string>>(new Set());
  const [canonicalPick, setCanonicalPick] = useState<string | null>(null);
  const [customCanonical, setCustomCanonical] = useState('');

  const { data: facet, isLoading } = useQuery({
    queryKey: ['containerTitleFacet', pid],
    queryFn: () => getContainerTitleFacet(pid),
    enabled: opened,
  });
  const rows = facet ?? [];

  // Fresh selection every time the modal is (re)opened.
  useEffect(() => {
    if (opened) {
      setChecked(new Set());
      setCanonicalPick(null);
      setCustomCanonical('');
    }
  }, [opened]);

  const toggle = (value: string) =>
    setChecked((prev) => {
      const next = new Set(prev);
      if (next.has(value)) next.delete(value);
      else next.add(value);
      return next;
    });

  const selectedRows = rows.filter((r) => checked.has(r.value));

  // Re-default the radio pick whenever the checked set changes, unless the current pick is still
  // among the (possibly new) checked values -- keeps an explicit user choice sticky while it's
  // still valid, same as MergeRecordsModal's survivor re-default.
  useEffect(() => {
    if (canonicalPick != null && checked.has(canonicalPick)) return;
    setCanonicalPick(defaultCanonical(selectedRows));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [checked]);

  // A typed custom value always overrides the radio pick.
  const canonical = customCanonical.trim() || canonicalPick || '';

  const mergeMut = useMutation({
    mutationFn: () => mergeContainerTitle(pid, canonical, [...checked]),
    onSuccess: (res) => {
      notifications.show({
        message: `Merged ${res.updated} reference${res.updated === 1 ? '' : 's'} into "${canonical}"`,
      });
      queryClient.invalidateQueries({ queryKey: ['containerTitleFacet', pid] });
      queryClient.invalidateQueries({ queryKey: ['references', pid] });
      setChecked(new Set());
      setCanonicalPick(null);
      setCustomCanonical('');
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Merge failed') }),
  });

  const canMerge = checked.size >= 2 && canonical.trim().length > 0;

  return (
    <Modal opened={opened} onClose={onClose} size="lg" title={<Text fw={600}>Reconcile journal names</Text>}>
      <Stack gap="md">
        {isLoading ? (
          <Text c="dimmed">Loading…</Text>
        ) : rows.length === 0 ? (
          <Text c="dimmed" size="sm">
            No journal names to reconcile.
          </Text>
        ) : (
          <Stack gap="xs">
            {rows.map((r) => (
              <Checkbox
                key={r.value}
                label={`${r.value} (${r.count})`}
                checked={checked.has(r.value)}
                onChange={() => toggle(r.value)}
              />
            ))}
          </Stack>
        )}

        {checked.size >= 2 && (
          <Stack gap="xs">
            <Radio.Group
              label="Canonical value -- every checked name is rewritten to this one"
              value={canonicalPick}
              onChange={setCanonicalPick}
            >
              <Stack gap={4} mt={4}>
                {selectedRows.map((r) => (
                  <Radio key={r.value} value={r.value} label={r.value} />
                ))}
              </Stack>
            </Radio.Group>
            <TextInput
              label="Or type a custom canonical value"
              description="Overrides the radio selection above if non-empty"
              value={customCanonical}
              onChange={(e) => setCustomCanonical(e.currentTarget.value)}
            />
          </Stack>
        )}

        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            Cancel
          </Button>
          <Button loading={mergeMut.isPending} disabled={!canMerge} onClick={() => mergeMut.mutate()}>
            Merge {checked.size} journal names into "{canonical || '…'}"
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
