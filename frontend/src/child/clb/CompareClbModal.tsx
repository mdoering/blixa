import {
  Anchor,
  Button,
  Group,
  Loader,
  Modal,
  Paper,
  SegmentedControl,
  Stack,
  Text,
  TextInput,
} from '@mantine/core';
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { useDebouncedValue } from '@mantine/hooks';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { notifications } from '@mantine/notifications';
import { messageFor } from '../../api/client';
import { getSynonyms, getUsage, usageCountsKey } from '../../api/usages';
import { childApi } from '../../api/childApi';
import { listTypeMaterial } from '../../api/typeMaterial';
import { listNameRelations } from '../../api/nameRelations';
import { getReference } from '../../api/references';
import { getProject } from '../../api/projects';
import { getPath } from '../../api/tree';
import {
  compareClbTaxon,
  copyFromClb,
  type ClbCopyPayload,
  searchClbAllDatasets,
  searchClbDatasets,
  searchClbUsages,
} from '../../api/clb';
import DatasetLabel from '../../clb/DatasetLabel';
import ClbComparisonView, { type CopyField, type CopyHandlers, type OursSide } from './ClbComparisonView';

const vernacularApi = childApi<{ name: string | null; language: string | null }>('vernaculars');

interface Props {
  pid: number;
  usageId: number;
  opened: boolean;
  onClose: () => void;
  // Puts a copied CLB value into the (open) edit form as an unsaved change -- see TaxonDetail. When
  // absent, single-value copying is not offered.
  onCopyField?: (field: CopyField | 'publishedInReferenceId', value: string | number) => void;
}

function HitRow({ label, sub, onClick }: { label: string; sub?: ReactNode; onClick: () => void }) {
  return (
    <Paper withBorder p="xs" radius="sm" onClick={onClick} style={{ cursor: 'pointer' }}>
      <Text size="sm">{label}</Text>
      {sub && (
        <Text size="xs" c="dimmed">
          {sub}
        </Text>
      )}
    </Paper>
  );
}

// Compare the focal taxon against a taxon in ChecklistBank: pick a target (across all datasets, or
// within a chosen dataset), then a side-by-side comparison (ClbComparisonView).
export default function CompareClbModal({ pid, usageId, opened, onClose, onCopyField }: Props) {
  const queryClient = useQueryClient();
  const { data: usage } = useQuery({
    queryKey: ['usage', pid, usageId],
    queryFn: () => getUsage(pid, usageId),
    enabled: opened,
  });
  const isAccepted = (usage?.status ?? '').toUpperCase() === 'ACCEPTED';
  // Our focal's accepted name when it is a synonym, lined up against CLB's (see ClbComparisonView).
  const acceptedId = !isAccepted ? usage?.acceptedParentIds?.[0] ?? null : null;
  const { data: accepted } = useQuery({
    queryKey: ['usage', pid, acceptedId],
    queryFn: () => getUsage(pid, acceptedId as number),
    enabled: opened && acceptedId != null,
  });
  const { data: path } = useQuery({
    queryKey: ['path', pid, usageId],
    queryFn: () => getPath(pid, usageId),
    enabled: opened,
  });
  const { data: synonyms } = useQuery({
    queryKey: ['synonyms', pid, usageId],
    queryFn: () => getSynonyms(pid, usageId),
    enabled: opened && isAccepted,
  });
  const { data: project } = useQuery({
    queryKey: ['project', pid],
    queryFn: () => getProject(pid),
    enabled: opened,
  });
  const favorites = project?.favoriteClbDatasets ?? [];
  const canEdit = project ? ['owner', 'editor'].includes(project.role) : false;

  // Our side of the supplementary rows -- same query keys as the TaxonDetail tabs, so these are
  // shared with (and refreshed alongside) them.
  const { data: vernaculars } = useQuery({
    queryKey: ['vernacular name', pid, usageId],
    queryFn: () => vernacularApi.list(pid, usageId),
    enabled: opened && isAccepted,
  });
  const { data: typeMaterial } = useQuery({
    queryKey: ['type material', pid, usageId],
    queryFn: () => listTypeMaterial(pid, usageId),
    enabled: opened,
  });
  const { data: nameRelations } = useQuery({
    queryKey: ['name relation', pid, usageId],
    queryFn: () => listNameRelations(pid, usageId),
    enabled: opened,
  });
  const pubRefId = usage?.publishedInReferenceId ?? null;
  const { data: pubRef } = useQuery({
    queryKey: ['reference', pid, pubRefId],
    queryFn: () => getReference(pid, pubRefId as number),
    enabled: opened && pubRefId != null,
  });

  // Values copied into the edit form but not saved yet: shown on our side so the row reads as
  // resolved (and its "«" goes away). Reset whenever the modal (re)opens.
  const [copied, setCopied] = useState<Partial<Record<CopyField | 'publishedIn', string>>>({});

  const focalName = usage?.scientificName ?? '';

  const ours: OursSide | null = useMemo(() => {
    if (!usage) return null;
    return {
      scientificName: usage.scientificName,
      authorship: usage.authorship,
      rank: usage.rank,
      status: usage.status,
      acceptedName: accepted
        ? [accepted.scientificName, accepted.authorship].filter(Boolean).join(' ')
        : null,
      // path is root>leaf including the focal itself; drop the focal for the higher classification
      classification: (path ?? [])
        .filter((p) => p.id !== usageId)
        .map((p) => ({ rank: p.rank, name: p.scientificName })),
      synonyms: (synonyms ?? []).map((s) => ({
        scientificName: s.scientificName,
        authorship: s.authorship,
        status: s.status,
      })),
      gender: usage.gender,
      etymology: usage.etymology,
      publishedIn: pubRef?.citation ?? null,
      publishedInPage: usage.publishedInPage,
      vernacularNames: vernaculars ?? [],
      typeMaterial: typeMaterial ?? [],
      nameRelations: (nameRelations ?? []).map((r) => ({ type: r.type, relatedName: r.relatedName })),
      ...copied,
    };
  }, [usage, accepted, path, synonyms, usageId, pubRef, vernaculars, typeMaterial, nameRelations, copied]);

  const [mode, setMode] = useState<'all' | 'dataset'>('all');
  const [nameQ, setNameQ] = useState('');
  const [datasetQ, setDatasetQ] = useState('');
  const [datasetKey, setDatasetKey] = useState<string | null>(null);
  const [datasetLabel, setDatasetLabel] = useState<string>('');
  const [target, setTarget] = useState<{ datasetKey: string; taxonId: string } | null>(null);
  const [debouncedName] = useDebouncedValue(nameQ, 300);
  const [debouncedDataset] = useDebouncedValue(datasetQ, 300);

  useEffect(() => {
    if (opened) {
      setCopied({});
      setNameQ(focalName);
      setTarget(null);
      setDatasetKey(null);
      setDatasetQ('');
      setMode('all');
    }
  }, [opened, focalName]);

  const allHits = useQuery({
    queryKey: ['clbAll', debouncedName],
    queryFn: () => searchClbAllDatasets(debouncedName),
    enabled: opened && mode === 'all' && !target && debouncedName.trim().length > 0,
  });
  const datasetHits = useQuery({
    queryKey: ['clbDatasets', debouncedDataset],
    queryFn: () => searchClbDatasets(debouncedDataset),
    enabled: opened && mode === 'dataset' && !datasetKey && debouncedDataset.trim().length > 0,
  });
  const inDatasetHits = useQuery({
    queryKey: ['clbUsages', datasetKey, debouncedName],
    queryFn: () => searchClbUsages(datasetKey as string, debouncedName),
    enabled: opened && mode === 'dataset' && !!datasetKey && !target && debouncedName.trim().length > 0,
  });

  const comparison = useQuery({
    queryKey: ['clbCompare', target?.datasetKey, target?.taxonId],
    queryFn: () => compareClbTaxon(target!.datasetKey, target!.taxonId),
    enabled: !!target,
  });

  // Record copies (synonyms / vernaculars / type material / the published-in reference) go straight
  // to the server; the affected lists are then refetched.
  const copyMutation = useMutation({
    mutationFn: (payload: Omit<ClbCopyPayload, 'datasetKey' | 'taxonId'>) =>
      copyFromClb(pid, usageId, { datasetKey: target!.datasetKey, taxonId: target!.taxonId, ...payload }),
    onSuccess: async (result, payload) => {
      if (payload.publishedIn && result.publishedInReferenceId != null) {
        onCopyField?.('publishedInReferenceId', result.publishedInReferenceId);
        setCopied((c) => ({ ...c, publishedIn: comparison.data?.publishedIn ?? '' }));
        await queryClient.invalidateQueries({ queryKey: ['refOptions', pid] });
      }
      for (const key of [
        ['synonyms', pid, usageId],
        ['synonymy', pid, usageId],
        ['vernacular name', pid, usageId],
        ['type material', pid, usageId],
        usageCountsKey(pid, usageId),
        ['changes', pid],
      ]) {
        await queryClient.invalidateQueries({ queryKey: key });
      }
      const s = result.summary;
      const n = s.synonyms + Object.values(s.children).reduce((a, b) => a + b, 0);
      notifications.show({
        color: s.issues.length ? 'orange' : undefined,
        message: payload.publishedIn
          ? 'Published-in reference created — save the form to keep it'
          : `Copied ${n} record${n === 1 ? '' : 's'} from ChecklistBank${s.issues.length ? ` (${s.issues.length} skipped)` : ''}`,
      });
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Copy failed') }),
  });

  const copyHandlers: CopyHandlers = {
    busy: copyMutation.isPending,
    ...(canEdit && onCopyField
      ? {
          field: (field: CopyField, value: string) => {
            onCopyField(field, value);
            setCopied((c) => ({ ...c, [field]: value }));
            notifications.show({ message: 'Copied into the form — save to keep it' });
          },
          publishedIn: () => copyMutation.mutate({ publishedIn: true }),
        }
      : {}),
    // Records attach to an accepted taxon only (same rule as Import from CLB).
    ...(canEdit && isAccepted
      ? {
          synonyms: (ids: string[]) => copyMutation.mutate({ synonymIds: ids }),
          vernaculars: (ids: string[]) => copyMutation.mutate({ vernacularIds: ids }),
          typeMaterial: (ids: string[]) => copyMutation.mutate({ typeMaterialIds: ids }),
        }
      : {}),
  };

  return (
    <Modal opened={opened} onClose={onClose} title="Compare with ChecklistBank" size="xl">
      <Stack>
        {!target && (
          <>
            <SegmentedControl
              value={mode}
              onChange={(v) => {
                setMode(v as 'all' | 'dataset');
                setDatasetKey(null);
              }}
              data={[
                { value: 'all', label: 'All datasets' },
                { value: 'dataset', label: 'By dataset' },
              ]}
            />

            {mode === 'dataset' && !datasetKey && (
              <>
                {favorites.length > 0 && (
                  <Group gap="xs">
                    <Text size="sm" c="dimmed">
                      Favorites:
                    </Text>
                    {favorites.map((f) => (
                      <Button
                        key={f.key}
                        size="xs"
                        variant="light"
                        onClick={() => {
                          setDatasetKey(f.key);
                          setDatasetLabel(f.title ?? f.key);
                        }}
                      >
                        {f.title ?? <DatasetLabel datasetKey={f.key} size="xs" />}
                      </Button>
                    ))}
                  </Group>
                )}
                <TextInput
                  aria-label="Search datasets"
                  placeholder="Search CLB datasets…"
                  value={datasetQ}
                  onChange={(e) => setDatasetQ(e.currentTarget.value)}
                />
                <Stack gap="xs">
                  {(datasetHits.data ?? []).map((d) => (
                    <HitRow
                      key={d.key}
                      label={d.title ?? d.key}
                      sub={d.alias ?? undefined}
                      onClick={() => {
                        setDatasetKey(d.key);
                        setDatasetLabel(d.title ?? d.key);
                      }}
                    />
                  ))}
                </Stack>
              </>
            )}

            {(mode === 'all' || datasetKey) && (
              <>
                {mode === 'dataset' && datasetKey && (
                  <Group gap="xs">
                    <Text size="sm">In {datasetLabel}</Text>
                    <Anchor size="sm" onClick={() => setDatasetKey(null)}>
                      change dataset
                    </Anchor>
                  </Group>
                )}
                <TextInput
                  aria-label="Search a name"
                  placeholder="Search a name…"
                  value={nameQ}
                  onChange={(e) => setNameQ(e.currentTarget.value)}
                />
                {(() => {
                  const hits = mode === 'all' ? allHits : inDatasetHits;
                  if (!debouncedName.trim()) return null;
                  if (hits.isFetching) return <Loader size="sm" />;
                  if (hits.isError)
                    return (
                      <Text size="sm" c="red">
                        ChecklistBank search failed
                      </Text>
                    );
                  return (hits.data ?? []).length === 0 ? (
                    <Text size="sm" c="dimmed">
                      No matching names{mode === 'dataset' ? ' in this dataset' : ''} — try another spelling.
                    </Text>
                  ) : (
                    <Text size="xs" c="dimmed">
                      Click a name to compare it.
                    </Text>
                  );
                })()}
                <Stack gap="xs">
                  {mode === 'all' &&
                    (allHits.data ?? []).map((h) => (
                      <HitRow
                        key={`${h.datasetKey}-${h.id}`}
                        label={`${h.scientificName ?? ''} ${h.authorship ?? ''}`}
                        sub={
                          <>
                            {`${h.rank ?? ''} · ${h.status ?? ''} · `}
                            <DatasetLabel datasetKey={h.datasetKey} size="xs" c="dimmed" />
                          </>
                        }
                        onClick={() => setTarget({ datasetKey: h.datasetKey, taxonId: h.id })}
                      />
                    ))}
                  {mode === 'dataset' &&
                    datasetKey &&
                    (inDatasetHits.data ?? []).map((h) => (
                      <HitRow
                        key={h.id}
                        label={h.scientificName ?? ''}
                        sub={`${h.rank ?? ''} · ${h.status ?? ''}`}
                        onClick={() => setTarget({ datasetKey, taxonId: h.id })}
                      />
                    ))}
                </Stack>
              </>
            )}
          </>
        )}

        {target && (
          <>
            <Button variant="subtle" size="xs" w="fit-content" onClick={() => setTarget(null)}>
              ← Pick another target
            </Button>
            {comparison.isLoading && <Loader />}
            {comparison.isError && (
              <Text size="sm" c="red">
                {messageFor(comparison.error, 'Could not load this taxon from ChecklistBank')}
              </Text>
            )}
            {comparison.data && ours && (
              <ClbComparisonView ours={ours} clb={comparison.data} copy={copyHandlers} />
            )}
          </>
        )}
      </Stack>
    </Modal>
  );
}
