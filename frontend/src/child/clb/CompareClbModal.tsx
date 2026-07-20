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
import { useEffect, useMemo, useState } from 'react';
import { useDebouncedValue } from '@mantine/hooks';
import { useQuery } from '@tanstack/react-query';
import { getSynonyms, getUsage } from '../../api/usages';
import { getPath } from '../../api/tree';
import {
  compareClbTaxon,
  searchClbAllDatasets,
  searchClbDatasets,
  searchClbUsages,
} from '../../api/clb';
import ClbComparisonView, { type OursSide } from './ClbComparisonView';

interface Props {
  pid: number;
  usageId: number;
  opened: boolean;
  onClose: () => void;
}

function HitRow({ label, sub, onClick }: { label: string; sub?: string; onClick: () => void }) {
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
export default function CompareClbModal({ pid, usageId, opened, onClose }: Props) {
  const { data: usage } = useQuery({
    queryKey: ['usage', pid, usageId],
    queryFn: () => getUsage(pid, usageId),
    enabled: opened,
  });
  const isAccepted = (usage?.status ?? '').toUpperCase() === 'ACCEPTED';
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

  const focalName = usage?.scientificName ?? '';

  const ours: OursSide | null = useMemo(() => {
    if (!usage) return null;
    return {
      scientificName: usage.scientificName,
      authorship: usage.authorship,
      rank: usage.rank,
      status: usage.status,
      // path is root>leaf including the focal itself; drop the focal for the higher classification
      classification: (path ?? [])
        .filter((p) => p.id !== usageId)
        .map((p) => ({ rank: p.rank, name: p.scientificName })),
      synonyms: (synonyms ?? []).map((s) => ({
        scientificName: s.scientificName,
        authorship: s.authorship,
        status: s.status,
      })),
    };
  }, [usage, path, synonyms, usageId]);

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
                      sub={d.alias ?? d.key}
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
                <Stack gap="xs">
                  {mode === 'all' &&
                    (allHits.data ?? []).map((h) => (
                      <HitRow
                        key={`${h.datasetKey}-${h.id}`}
                        label={`${h.scientificName ?? ''} ${h.authorship ?? ''}`}
                        sub={`${h.rank ?? ''} · ${h.status ?? ''} · ${h.datasetTitle ?? `dataset ${h.datasetKey}`}`}
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
            {comparison.data && ours && <ClbComparisonView ours={ours} clb={comparison.data} />}
          </>
        )}
      </Stack>
    </Modal>
  );
}
