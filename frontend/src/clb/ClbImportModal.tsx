import {
  Alert,
  Autocomplete,
  Button,
  Checkbox,
  Divider,
  Group,
  List,
  Loader,
  Modal,
  Radio,
  SegmentedControl,
  Select,
  Stack,
  Text,
  TextInput,
} from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconAlertTriangle, IconCircleCheck } from '@tabler/icons-react';
import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { messageFor } from '../api/client';
import {
  clbImport,
  parseClbUrl,
  resolveClbTaxon,
  searchClbDatasets,
  searchClbUsages,
  type ClbDatasetHit,
  type ClbEntityType,
  type ClbImportMode,
  type ClbImportSummary,
  type ClbUsageHit,
} from '../api/clb';

export interface ClbImportFocalUsage {
  id: number;
  scientificName: string | null;
}

export interface ClbImportModalProps {
  projectId: number;
  focalUsage: ClbImportFocalUsage;
  opened: boolean;
  onClose: () => void;
}

type SourceTab = 'url' | 'search';

interface Source {
  datasetKey: string;
  sourceTaxonId: string;
}

const ENTITY_TYPE_OPTIONS: { value: ClbEntityType; label: string }[] = [
  { value: 'synonyms', label: 'Synonyms' },
  { value: 'vernacular', label: 'Vernacular names' },
  { value: 'distribution', label: 'Distributions' },
  { value: 'typeMaterial', label: 'Type material' },
  { value: 'media', label: 'Media' },
  { value: 'estimate', label: 'Estimates' },
  { value: 'property', label: 'Properties' },
  { value: 'nameRelation', label: 'Name relations' },
];
const ALL_ENTITY_TYPES = ENTITY_TYPE_OPTIONS.map((o) => o.value);

const RANK_OPTIONS = [
  'kingdom',
  'phylum',
  'class',
  'order',
  'family',
  'genus',
  'species',
  'subspecies',
  'variety',
  'form',
].map((r) => ({ value: r, label: r }));

function datasetLabel(d: ClbDatasetHit): string {
  return d.alias ? `${d.title ?? d.key} (${d.alias})` : (d.title ?? d.key);
}

function usageLabel(u: ClbUsageHit): string {
  return u.rank ? `${u.scientificName ?? u.id} (${u.rank})` : (u.scientificName ?? u.id);
}

// Import from ChecklistBank: pick a source CLB taxon (either by pasting a checklistbank.org/
// catalogueoflife.org URL, or by searching a dataset then a taxon within it), a mode (insert the
// taxon + its subtree / insert only its children / attach the taxon's own synonymy+info onto the
// focal usage), and -- for the update mode -- which kinds of supplementary data to bring over, then
// POSTs it all to the backend's single synchronous clb-import endpoint. Mirrors CreateNameModal's
// shape (a Mantine Modal wrapping a useMutation) but with a richer, two-part source picker.
export default function ClbImportModal({ projectId, focalUsage, opened, onClose }: ClbImportModalProps) {
  const queryClient = useQueryClient();

  const [sourceTab, setSourceTab] = useState<SourceTab>('url');

  // --- paste-URL path ---------------------------------------------------------------------
  const [pastedUrl, setPastedUrl] = useState('');
  // Debounced (like the search fields below): parseClbUrl's id group ([^/?#]+) already matches
  // after just the FIRST character of a taxon id is typed, so parsing the raw, un-debounced value
  // would fire a resolveClbTaxon request again on every subsequent keystroke while the rest of the
  // id is still being typed/pasted -- not just once the paste/typing has actually settled.
  const [debouncedPastedUrl] = useDebouncedValue(pastedUrl, 300);
  const parsedUrl = useMemo(() => parseClbUrl(debouncedPastedUrl), [debouncedPastedUrl]);
  const {
    data: resolved,
    isFetching: resolving,
    isError: resolveErrored,
    error: resolveError,
  } = useQuery({
    queryKey: ['clbResolve', parsedUrl?.datasetKey, parsedUrl?.taxonId],
    queryFn: () => resolveClbTaxon(parsedUrl!.datasetKey, parsedUrl!.taxonId),
    enabled: parsedUrl != null,
  });

  // --- search path --------------------------------------------------------------------------
  const [datasetQuery, setDatasetQuery] = useState('');
  const [debouncedDatasetQuery] = useDebouncedValue(datasetQuery, 300);
  const [selectedDataset, setSelectedDataset] = useState<ClbDatasetHit | null>(null);
  const { data: datasetHits } = useQuery({
    queryKey: ['clbDatasets', debouncedDatasetQuery],
    queryFn: () => searchClbDatasets(debouncedDatasetQuery),
    enabled: sourceTab === 'search' && debouncedDatasetQuery.trim().length > 0,
  });
  const datasetByLabel = useMemo(
    () => new Map((datasetHits ?? []).map((d) => [datasetLabel(d), d])),
    [datasetHits],
  );

  const [taxonQuery, setTaxonQuery] = useState('');
  const [debouncedTaxonQuery] = useDebouncedValue(taxonQuery, 300);
  const [rankFilter, setRankFilter] = useState<string | null>(null);
  const [selectedUsage, setSelectedUsage] = useState<ClbUsageHit | null>(null);
  const { data: usageHits } = useQuery({
    queryKey: ['clbUsages', selectedDataset?.key, debouncedTaxonQuery, rankFilter],
    queryFn: () => searchClbUsages(selectedDataset!.key, debouncedTaxonQuery, rankFilter ?? undefined),
    enabled: sourceTab === 'search' && selectedDataset != null && debouncedTaxonQuery.trim().length > 0,
  });
  const usageByLabel = useMemo(
    () => new Map((usageHits ?? []).map((u) => [usageLabel(u), u])),
    [usageHits],
  );

  const source: Source | null =
    sourceTab === 'url'
      ? resolved
        ? { datasetKey: resolved.datasetKey, sourceTaxonId: resolved.taxonId }
        : null
      : selectedDataset && selectedUsage
        ? { datasetKey: selectedDataset.key, sourceTaxonId: selectedUsage.id }
        : null;

  // --- mode + entity types --------------------------------------------------------------------
  const [mode, setMode] = useState<ClbImportMode>('TAXON_SUBTREE');
  const [entityTypes, setEntityTypes] = useState<ClbEntityType[]>(ALL_ENTITY_TYPES);

  const focalName = focalUsage.scientificName ?? 'this taxon';

  const mutation = useMutation({
    mutationFn: () => {
      if (!source) throw new Error('no source selected');
      return clbImport(projectId, focalUsage.id, {
        datasetKey: source.datasetKey,
        sourceTaxonId: source.sourceTaxonId,
        mode,
        entityTypes: mode === 'UPDATE_FOCAL' ? entityTypes : undefined,
      });
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['treeChildren', projectId] });
      await queryClient.invalidateQueries({ queryKey: ['treeRoots', projectId] });
      await queryClient.invalidateQueries({ queryKey: ['usageSearch', projectId] });
      await queryClient.invalidateQueries({ queryKey: ['usage', projectId, focalUsage.id] });
    },
  });

  const summary: ClbImportSummary | undefined = mutation.data;

  const handleClose = () => {
    mutation.reset();
    onClose();
  };

  return (
    <Modal
      opened={opened}
      onClose={handleClose}
      size="lg"
      title={
        <Text fw={600}>
          Import from ChecklistBank into <Text span fs="italic" inherit>{focalName}</Text>
        </Text>
      }
    >
      <Stack gap="md">
        {summary ? (
          <ImportSummaryView summary={summary} />
        ) : (
          <>
            <SegmentedControl
              value={sourceTab}
              onChange={(v) => setSourceTab(v as SourceTab)}
              data={[
                { value: 'url', label: 'Paste URL' },
                { value: 'search', label: 'Search' },
              ]}
            />

            {sourceTab === 'url' && (
              <Stack gap="xs">
                <TextInput
                  label="ChecklistBank or catalogueoflife.org URL"
                  placeholder="https://www.checklistbank.org/dataset/3LXR/taxon/6W3C4"
                  value={pastedUrl}
                  onChange={(e) => setPastedUrl(e.currentTarget.value)}
                  error={pastedUrl.trim() && !parsedUrl ? 'Not a recognized ChecklistBank URL' : undefined}
                />
                {parsedUrl && resolving && (
                  <Group gap="xs">
                    <Loader size="xs" />
                    <Text size="sm" c="dimmed">
                      Resolving…
                    </Text>
                  </Group>
                )}
                {parsedUrl && resolveErrored && (
                  <Text size="sm" c="red">
                    {messageFor(resolveError, 'Could not resolve that taxon')}
                  </Text>
                )}
                {resolved && (
                  <Text size="sm">
                    <Text span fw={600} fs="italic" inherit>
                      {resolved.scientificName ?? resolved.taxonId}
                    </Text>
                    {resolved.rank ? ` (${resolved.rank})` : ''} — dataset {resolved.datasetKey}
                  </Text>
                )}
              </Stack>
            )}

            {sourceTab === 'search' && (
              <Stack gap="xs">
                <Autocomplete
                  label="Dataset"
                  placeholder="Search datasets…"
                  value={datasetQuery}
                  onChange={(value) => {
                    setDatasetQuery(value);
                    const hit = datasetByLabel.get(value) ?? null;
                    setSelectedDataset(hit);
                    setSelectedUsage(null);
                  }}
                  data={[...datasetByLabel.keys()]}
                />
                <Group align="flex-end" gap="xs">
                  <Autocomplete
                    style={{ flex: 1 }}
                    label="Taxon"
                    placeholder={selectedDataset ? 'Search taxa…' : 'Pick a dataset first'}
                    disabled={!selectedDataset}
                    value={taxonQuery}
                    onChange={(value) => {
                      setTaxonQuery(value);
                      setSelectedUsage(usageByLabel.get(value) ?? null);
                    }}
                    data={[...usageByLabel.keys()]}
                  />
                  <Select
                    label="Rank"
                    placeholder="Any"
                    data={RANK_OPTIONS}
                    value={rankFilter}
                    onChange={setRankFilter}
                    clearable
                    style={{ width: 140 }}
                  />
                </Group>
              </Stack>
            )}

            <Divider />

            <Radio.Group
              label="What to import"
              value={mode}
              onChange={(v) => setMode(v as ClbImportMode)}
            >
              <Stack gap="xs" mt="xs">
                <Radio
                  value="TAXON_SUBTREE"
                  label={
                    <>
                      Taxon + its subtree (as children of <i>{focalName}</i>)
                    </>
                  }
                />
                <Radio value="CHILDREN_ONLY" label="Only its children" />
                <Radio
                  value="UPDATE_FOCAL"
                  label={
                    <>
                      Update <i>{focalName}</i> (import synonymy + info)
                    </>
                  }
                />
              </Stack>
            </Radio.Group>

            {mode === 'UPDATE_FOCAL' && (
              <Checkbox.Group
                label="Include"
                value={entityTypes}
                onChange={(v) => setEntityTypes(v as ClbEntityType[])}
              >
                <Group mt="xs" gap="md">
                  {ENTITY_TYPE_OPTIONS.map((o) => (
                    <Checkbox key={o.value} value={o.value} label={o.label} />
                  ))}
                </Group>
              </Checkbox.Group>
            )}

            {mutation.isError && (
              <Alert color="red" icon={<IconAlertTriangle size={16} />}>
                {messageFor(mutation.error, 'Import failed')}
              </Alert>
            )}
          </>
        )}

        <Group justify="flex-end">
          {summary ? (
            <Button onClick={handleClose}>Close</Button>
          ) : (
            <>
              <Button variant="default" onClick={handleClose}>
                Cancel
              </Button>
              <Button
                onClick={() => mutation.mutate()}
                loading={mutation.isPending}
                disabled={!source}
              >
                Import
              </Button>
            </>
          )}
        </Group>
      </Stack>
    </Modal>
  );
}

function ImportSummaryView({ summary }: { summary: ClbImportSummary }) {
  const childEntries = Object.entries(summary.children).filter(([, count]) => count > 0);
  return (
    <Alert color="green" icon={<IconCircleCheck size={16} />} title="Import complete">
      <Stack gap="xs">
        <Text size="sm">
          {summary.nameUsages} name usage(s), {summary.synonyms} synonym(s), {summary.references}{' '}
          reference(s) imported.
        </Text>
        {childEntries.length > 0 && (
          <Text size="sm">
            {childEntries.map(([k, v]) => `${v} ${k}`).join(', ')}
          </Text>
        )}
        {summary.issues.length > 0 && (
          <>
            <Text size="sm" fw={600} c="orange">
              {summary.issues.length} issue(s):
            </Text>
            <List size="sm" spacing={2}>
              {summary.issues.map((issue, i) => (
                <List.Item key={i}>
                  {issue.entity} ({issue.sourceId}): {issue.message}
                </List.Item>
              ))}
            </List>
          </>
        )}
      </Stack>
    </Alert>
  );
}
