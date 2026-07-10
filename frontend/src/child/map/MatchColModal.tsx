import { useEffect, useState } from 'react';
import { Button, Center, Group, Loader, Modal, Radio, Stack, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, messageFor } from '../../api/client';
import { colMatch, getUsage, updateIdentifiers } from '../../api/usages';
import { withColId } from './mapUrls';

export interface MatchColModalProps {
  pid: number;
  usageId: number;
  opened: boolean;
  onClose: () => void;
}

// Confirms a COL match candidate and writes it to the usage's alternativeId as `col:<id>`
// (see backend ColMatchService for the candidates and NameUsageService.setIdentifiers for the
// optimistic-locked write). Candidates come back best-match-first; alternatives disambiguated by
// their classification (root > leaf) for homonym cases.
export default function MatchColModal({ pid, usageId, opened, onClose }: MatchColModalProps) {
  const queryClient = useQueryClient();
  const [selected, setSelected] = useState<string | null>(null);

  const matchQuery = useQuery({
    queryKey: ['colMatch', pid, usageId],
    queryFn: () => colMatch(pid, usageId),
    enabled: opened,
  });
  // Reuses the ['usage', pid, usageId] query TaxonDetail already loaded (react-query serves it
  // from cache when fresh); fetches directly if the modal is opened without that parent mounted.
  const usageQuery = useQuery({
    queryKey: ['usage', pid, usageId],
    queryFn: () => getUsage(pid, usageId),
    enabled: opened,
  });

  const candidates = matchQuery.data ?? [];

  // Preselect the best match (first candidate) whenever the modal (re)opens with fresh data.
  useEffect(() => {
    if (opened) {
      setSelected(candidates.length > 0 ? candidates[0].colId : null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [opened, matchQuery.data]);

  const mutation = useMutation({
    mutationFn: () => {
      const usage = usageQuery.data;
      if (!usage || !selected) throw new Error('not ready');
      // Full replace (see updateIdentifiers): carry over every existing non-col id, drop any
      // stale col: entry, append the picked one.
      const merged = withColId(usage.alternativeId ?? [], selected);
      return updateIdentifiers(pid, usageId, merged, usage.version);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['usage', pid, usageId] });
      await queryClient.invalidateQueries({ queryKey: ['map', pid, usageId] });
      notifications.show({ message: 'Matched to COL' });
      onClose();
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 409) {
        notifications.show({
          color: 'orange',
          message: 'This taxon changed since it was loaded — close and reopen to retry.',
        });
        return;
      }
      notifications.show({ color: 'red', message: messageFor(e, 'Could not save the match') });
    },
  });

  const loading = matchQuery.isLoading;

  return (
    <Modal opened={opened} onClose={onClose} title="Match to Catalogue of Life" size="lg">
      <Stack gap="md">
        {loading && (
          <Center h={80}>
            <Loader size="sm" />
          </Center>
        )}
        {matchQuery.isError && <Text c="red">Could not load COL matches.</Text>}
        {matchQuery.isSuccess && candidates.length === 0 && (
          <Text c="dimmed">No COL match found.</Text>
        )}
        {candidates.length > 0 && (
          <Radio.Group value={selected} onChange={setSelected}>
            <Stack gap="sm">
              {candidates.map((c) => (
                <Radio
                  key={c.colId}
                  value={c.colId}
                  label={
                    <Stack gap={0}>
                      <Text size="sm">
                        <Text span fs="italic" inherit>
                          {c.name}
                        </Text>
                        {c.authorship ? ` ${c.authorship}` : ''} · {c.rank ?? '—'} ·{' '}
                        {c.status ?? '—'} · {c.matchType}
                      </Text>
                      {c.classification && (
                        <Text size="xs" c="dimmed">
                          {c.classification}
                        </Text>
                      )}
                    </Stack>
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
          {candidates.length > 0 && (
            <Button
              onClick={() => mutation.mutate()}
              loading={mutation.isPending}
              disabled={!selected || !usageQuery.data}
            >
              Use this
            </Button>
          )}
        </Group>
      </Stack>
    </Modal>
  );
}
