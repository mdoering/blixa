import { Badge, Button, Group, Modal, Radio, Stack, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useEffect, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { messageFor } from '../api/client';
import {
  mergeReferences,
  mergeUsages,
  previewReferenceMerge,
  previewUsageMerge,
  type MergeCandidate,
} from '../api/merge';

export interface MergeRecordsModalProps {
  entity: 'usage' | 'reference';
  pid: number;
  ids: number[];
  opened: boolean;
  onClose: () => void;
  onDone: () => void;
}

function totalCounts(c: MergeCandidate): number {
  return Object.values(c.counts).reduce((sum, n) => sum + n, 0);
}

// Pick the most-connected candidate as the default survivor -- the one with the most references
// into it elsewhere in the tree/reference graph is the safest default to keep, since it's the one
// most other things would need re-pointing to if a *different* record were kept instead. Ties
// break on the lowest id (deterministic, and matches the backend's own id-ascending preview order).
function defaultSurvivorId(candidates: MergeCandidate[]): number | null {
  if (candidates.length === 0) return null;
  return [...candidates].sort((a, b) => totalCounts(b) - totalCounts(a) || a.id - b.id)[0].id;
}

// Plain-string label, e.g. for the "Merge N records into …" button caption.
function labelFor(entity: 'usage' | 'reference', c: MergeCandidate): string {
  if (entity === 'usage') {
    return [c.scientificName, c.authorship].filter(Boolean).join(' ') || `#${c.id}`;
  }
  return c.citation || c.doi || `#${c.id}`;
}

// Same label, but as separate scientificName/authorship text nodes -- mirrors NameSearchPage's own
// "Scientific name" column rendering, and (unlike the concatenated string above) keeps
// "Abies alba" queryable as exact text in the candidate list.
function CandidateLabel({ entity, c }: { entity: 'usage' | 'reference'; c: MergeCandidate }) {
  if (entity === 'reference') {
    return <Text size="sm">{c.citation || c.doi || `#${c.id}`}</Text>;
  }
  return (
    <Group gap={6} wrap="nowrap">
      <Text size="sm" fs="italic">
        {c.scientificName}
      </Text>
      {c.authorship && (
        <Text size="xs" c="dimmed">
          {c.authorship}
        </Text>
      )}
    </Group>
  );
}

// Preview -> pick survivor -> merge for a set of duplicate name-usage or reference records within
// one project. Unlike MergeModal.tsx (a whole *other* project's plan applied into this one), this
// is a same-project dedupe: every non-survivor record's associations are re-pointed onto the
// survivor and the rest are deleted (see backend MergeRecordsService).
export default function MergeRecordsModal({
  entity,
  pid,
  ids,
  opened,
  onClose,
  onDone,
}: MergeRecordsModalProps) {
  const [survivorId, setSurvivorId] = useState<number | null>(null);

  const { data: candidates, isLoading } = useQuery({
    queryKey: ['mergePreview', entity, pid, ids],
    queryFn: () =>
      entity === 'usage' ? previewUsageMerge(pid, ids) : previewReferenceMerge(pid, ids),
    enabled: opened && ids.length >= 2,
  });

  // Reset on open (a fresh selection) and re-default once the preview lands.
  useEffect(() => {
    if (opened) setSurvivorId(null);
  }, [opened]);
  useEffect(() => {
    if (candidates && survivorId == null) setSurvivorId(defaultSurvivorId(candidates));
  }, [candidates, survivorId]);

  const mergeMut = useMutation({
    mutationFn: () => {
      if (survivorId == null) throw new Error('no survivor selected');
      return entity === 'usage'
        ? mergeUsages(pid, survivorId, ids)
        : mergeReferences(pid, survivorId, ids);
    },
    onSuccess: (res) => {
      notifications.show({
        message: `Merged ${res.mergedCount} record${res.mergedCount === 1 ? '' : 's'}`,
      });
      onDone();
      onClose();
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Merge failed') }),
  });

  const survivor = candidates?.find((c) => c.id === survivorId);

  return (
    <Modal opened={opened} onClose={onClose} size="lg" title={<Text fw={600}>Merge {ids.length} records</Text>}>
      <Stack gap="md">
        {isLoading || !candidates ? (
          <Text c="dimmed">Loading…</Text>
        ) : (
          <Radio.Group
            label="Keep this record as the survivor -- the others are merged into it and removed."
            value={survivorId != null ? String(survivorId) : null}
            onChange={(v) => setSurvivorId(Number(v))}
          >
            <Stack gap="xs" mt="xs">
              {candidates.map((c) => (
                <Radio
                  key={c.id}
                  value={String(c.id)}
                  label={
                    <Group gap={6} wrap="wrap">
                      <Text size="sm" c="dimmed">
                        #{c.id}
                      </Text>
                      {c.alternativeId && c.alternativeId.length > 0 && (
                        <Text size="xs" c="dimmed">
                          ({c.alternativeId.join(', ')})
                        </Text>
                      )}
                      <CandidateLabel entity={entity} c={c} />
                      {entity === 'usage' && c.rank && (
                        <Badge size="xs" variant="light" color="blue">
                          {c.rank}
                        </Badge>
                      )}
                      {entity === 'usage' && c.status && (
                        <Badge size="xs" variant="light" color="gray">
                          {c.status}
                        </Badge>
                      )}
                      {Object.entries(c.counts)
                        .filter(([, n]) => n > 0)
                        .map(([k, n]) => (
                          <Badge key={k} size="xs" variant="outline">
                            {k} {n}
                          </Badge>
                        ))}
                    </Group>
                  }
                />
              ))}
            </Stack>
          </Radio.Group>
        )}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            Cancel
          </Button>
          <Button
            loading={mergeMut.isPending}
            disabled={!candidates || survivorId == null}
            onClick={() => mergeMut.mutate()}
          >
            Merge {ids.length} records into {survivor ? labelFor(entity, survivor) : '…'}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
