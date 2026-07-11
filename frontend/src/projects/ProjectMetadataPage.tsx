import {
  ActionIcon,
  Alert,
  Anchor,
  Autocomplete,
  Badge,
  Button,
  Group,
  Progress,
  SimpleGrid,
  Stack,
  Select,
  Switch,
  Text,
  Textarea,
  TextInput,
  Title,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { modals } from '@mantine/modals';
import { notifications } from '@mantine/notifications';
import { IconPlus, IconTrash } from '@tabler/icons-react';
import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { deleteProject, getProject, updateMetadata } from '../api/projects';
import { getIdScopes } from '../api/coldp';
import { getColMatchRun, getLatestColMatch, startColMatch } from '../api/col';
import { exportFileUrl, getExportRun, getLatestExport, startExport } from '../api/export';
import { messageFor } from '../api/client';
import type { UpdateMetadataPayload } from '../api/types';
import MergeModal from '../merge/MergeModal';
import { NOM_CODES } from './CreateProjectModal';

// The CLB dataset key COL's own checklist is published under -- "col" conventionally aliases
// this dataset, so picking that scope defaults its Dataset key field to save a lookup (see the
// scope Autocomplete's onChange below). Editable afterwards like any other row.
const COL_DATASET_KEY = '3LXR';

const LICENSES = ['CC0-1.0', 'CC-BY-4.0'];
// Poll interval for the bulk "Match all identifiers" run while it's RUNNING -- a live but not
// chatty progress indicator over what is, per usage, a sequential CLB round-trip (see
// ColMatchAsyncConfig's single-thread pool on the backend).
const COL_MATCH_POLL_MS = 1500;
// Poll interval for the "Export ColDP" run while it's RUNNING -- the export job has no
// total/processed tally (unlike col-match), so this just drives a live status check rather than a
// progress bar; see ExportAsyncConfig's single-thread pool on the backend.
const EXPORT_POLL_MS = 1500;

// export_run.fileSize is bytes -- a friendly human-readable label for the DONE summary.
function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const kb = bytes / 1024;
  if (kb < 1024) return `${kb.toFixed(1)} KB`;
  return `${(kb / 1024).toFixed(1)} MB`;
}

export default function ProjectMetadataPage() {
  const { projectId } = useParams();
  const id = Number(projectId);
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  const form = useForm<UpdateMetadataPayload>({
    initialValues: {
      title: '',
      alias: undefined,
      description: undefined,
      nomCode: undefined,
      license: undefined,
      geographicScope: undefined,
      taxonomicScope: undefined,
      gbifOccurrenceLayer: true,
      identifierScopes: [],
    },
    validate: {
      title: (v) => (v ? null : 'Required'),
    },
  });

  const { data } = useQuery({ queryKey: ['project', id], queryFn: () => getProject(id) });
  const canEdit = data ? ['owner', 'editor'].includes(data.role) : false;
  // Deleting a project is owner-only (backend ProjectService.delete enforces it too); it cascades
  // to every project-scoped row, so it lives behind a typed confirmation in the Danger zone below.
  const isOwner = data?.role === 'owner';

  // Seeds each scope row's Autocomplete suggestion list -- the project's own already-configured
  // scopes still populate their row even if they're not in this vocab (e.g. a legacy custom
  // entry), since Autocomplete's `data` is suggestions only, not a value restriction.
  const { data: idScopesVocab } = useQuery({ queryKey: ['idScopes'], queryFn: getIdScopes });

  useEffect(() => {
    if (data) {
      const values = Object.fromEntries(
        Object.entries(data).map(([k, v]) => [k, v ?? undefined]),
      ) as UpdateMetadataPayload;
      // The row editor is a controlled array of {scope, datasetKey} inputs -- null/absent must
      // become [], and each row's datasetKey (nullable on the wire) must become '' so the
      // TextInput below stays controlled.
      values.identifierScopes = (data.identifierScopes ?? []).map((s) => ({
        scope: s.scope,
        datasetKey: s.datasetKey ?? '',
      }));
      form.setValues(values);
      form.resetDirty(values);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data]);

  const mutation = useMutation({
    mutationFn: (values: UpdateMetadataPayload) => updateMetadata(id, values),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['project', id] });
      await queryClient.invalidateQueries({ queryKey: ['projects'] });
      notifications.show({ message: 'Saved' });
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Save failed') }),
  });

  // Bulk "Match all identifiers" job: startColMatch kicks it off and returns the run's id, which
  // then drives a polling GET while the run is still RUNNING (react-query refetchInterval, backend
  // ColMatchRunController). The run id lives in component state rather than the query key alone so
  // a second click restarts polling for the NEW run. The endpoint/type names stay COL-specific
  // (col-match, ColMatchRun) even though the job now matches every matchable identifier scope --
  // only the UI copy is generalized here.
  const [matchRunId, setMatchRunId] = useState<number | null>(null);

  // Latest-run view: on mount, look up the project's most recent run (if any) so reopening the page
  // resumes a still-RUNNING run's progress display, or shows the last run's summary, without the
  // user having to click the button again. Seeded into matchRunId exactly once (seededLatest guard)
  // so a run the user starts in THIS session is never clobbered by a slow-to-resolve latest lookup
  // racing behind it -- by the time that lookup resolves, matchRunId is already non-null from
  // startMatchMut's onSuccess, and the functional setMatchRunId below leaves it alone. Gated on
  // canEdit: the whole match-run section below is canEdit-only, so a viewer never needs this fetch.
  const { data: latestMatchRun, isSuccess: latestMatchLoaded } = useQuery({
    queryKey: ['colMatchLatest', id],
    queryFn: () => getLatestColMatch(id),
    enabled: canEdit,
  });
  const seededLatest = useRef(false);
  useEffect(() => {
    if (latestMatchLoaded && !seededLatest.current) {
      seededLatest.current = true;
      if (latestMatchRun) {
        setMatchRunId((current) => current ?? latestMatchRun.id);
      }
    }
  }, [latestMatchLoaded, latestMatchRun]);

  const { data: matchRun } = useQuery({
    queryKey: ['colMatchRun', id, matchRunId],
    queryFn: () => getColMatchRun(id, matchRunId as number),
    enabled: matchRunId != null,
    refetchInterval: (query) => (query.state.data?.status === 'RUNNING' ? COL_MATCH_POLL_MS : false),
  });

  // Once the run leaves RUNNING (DONE or FAILED), the col_* issue flags it wrote are final for this
  // run -- refresh the Issues view's data so a user who then navigates there sees them without a
  // manual reload.
  useEffect(() => {
    if (matchRun && matchRun.status !== 'RUNNING') {
      queryClient.invalidateQueries({ queryKey: ['issues', id] });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [matchRun?.status, matchRun?.id]);

  const startMatchMut = useMutation({
    mutationFn: () => startColMatch(id),
    onSuccess: (run) => setMatchRunId(run.id),
    onError: (e) =>
      notifications.show({
        color: 'red',
        message: messageFor(e, 'Match all identifiers failed to start'),
      }),
  });
  const matchRunning = startMatchMut.isPending || matchRun?.status === 'RUNNING';

  // A run is a no-op (total 0) unless the PERSISTED project has at least one identifier scope with
  // a non-blank dataset key -- derived from `data` (not `form.values`, which can drift from the
  // saved project before the user hits Save) so the button's enabled state always matches what a
  // click would actually do.
  const hasMatchableScope = (data?.identifierScopes ?? []).some(
    (s) => (s.datasetKey ?? '').trim() !== '',
  );

  // "Export ColDP" job: same start-then-poll shape as "Match all identifiers" above (startExport ->
  // react-query refetchInterval while RUNNING), but open to ANY project member (export only reads
  // project data -- see ExportRunService.start's requireRole, vs col-match's owner/editor gate), so
  // this section renders outside the canEdit-only block below.
  const [exportRunId, setExportRunId] = useState<number | null>(null);

  // Latest-run view: on mount, look up the project's most recent export (if any) so reopening the
  // page resumes a still-RUNNING export or shows the last one's download link, without the user
  // having to click the button again. Seeded into exportRunId exactly once (seededLatestExport
  // guard), same race-avoidance reasoning as seededLatest above.
  const { data: latestExportRun, isSuccess: latestExportLoaded } = useQuery({
    queryKey: ['exportLatest', id],
    queryFn: () => getLatestExport(id),
  });
  const seededLatestExport = useRef(false);
  useEffect(() => {
    if (latestExportLoaded && !seededLatestExport.current) {
      seededLatestExport.current = true;
      if (latestExportRun) {
        setExportRunId((current) => current ?? latestExportRun.id);
      }
    }
  }, [latestExportLoaded, latestExportRun]);

  const { data: exportRun } = useQuery({
    queryKey: ['exportRun', id, exportRunId],
    queryFn: () => getExportRun(id, exportRunId as number),
    enabled: exportRunId != null,
    refetchInterval: (query) => (query.state.data?.status === 'RUNNING' ? EXPORT_POLL_MS : false),
  });

  const startExportMut = useMutation({
    mutationFn: () => startExport(id),
    onSuccess: (run) => setExportRunId(run.id),
    onError: (e) =>
      notifications.show({ color: 'red', message: messageFor(e, 'Export failed to start') }),
  });
  const exportRunning = startExportMut.isPending || exportRun?.status === 'RUNNING';

  // Supervised project merge (owner/editor only, same tier as "Match all identifiers"): opens
  // MergeModal, which owns its own start/poll/apply state scoped to this project as the target.
  const [merging, setMerging] = useState(false);

  const deleteMut = useMutation({
    mutationFn: () => deleteProject(id),
    onSuccess: async () => {
      // Drop the now-deleted project from the sidebar/list cache, then leave the (now 404ing)
      // project layout for the project list.
      await queryClient.invalidateQueries({ queryKey: ['projects'] });
      notifications.show({ message: 'Project deleted' });
      navigate('/');
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Delete failed') }),
  });

  const confirmDelete = () =>
    modals.openConfirmModal({
      title: 'Delete project',
      children: (
        <Text size="sm">
          Permanently delete <b>{data?.title}</b> and all of its names, references, and history?
          This cannot be undone.
        </Text>
      ),
      labels: { confirm: 'Delete project', cancel: 'Cancel' },
      confirmProps: { color: 'red' },
      onConfirm: () => deleteMut.mutate(),
    });

  return (
    <Stack style={{ maxWidth: 720 }} gap="xl">
      <Stack gap="xs">
        <Group justify="space-between">
          <Title order={4} m={0}>
            Export ColDP
          </Title>
          <Button
            variant="default"
            loading={startExportMut.isPending}
            disabled={exportRunning}
            onClick={() => startExportMut.mutate()}
          >
            Export ColDP
          </Button>
        </Group>
        {exportRun?.status === 'RUNNING' && (
          <Text size="sm" c="dimmed">
            Exporting…
          </Text>
        )}
        {exportRun?.status === 'DONE' && (
          <Stack gap={4}>
            <Group gap="xs">
              <Text size="sm">
                {exportRun.fileName}
                {exportRun.fileSize != null && ` (${formatFileSize(exportRun.fileSize)})`}
              </Text>
              <Anchor href={exportFileUrl(id, exportRun.id)} download>
                Download
              </Anchor>
            </Group>
            <Group gap="xs">
              <Badge color="blue" variant="light">
                usages {exportRun.nameUsageCount}
              </Badge>
              <Badge color="grape" variant="light">
                references {exportRun.referenceCount}
              </Badge>
            </Group>
          </Stack>
        )}
        {exportRun?.status === 'FAILED' && (
          <Alert color="red" title="Export ColDP failed">
            {exportRun.error ?? 'Unknown error'}
          </Alert>
        )}
      </Stack>

      {canEdit && (
        <Stack gap="xs">
          <Group justify="space-between">
            <Title order={4} m={0}>
              Match all identifiers
            </Title>
            <Button
              variant="default"
              loading={startMatchMut.isPending}
              disabled={matchRunning || !hasMatchableScope}
              onClick={() => startMatchMut.mutate()}
            >
              Match all identifiers
            </Button>
          </Group>
          {!hasMatchableScope && (
            <Text size="sm" c="dimmed">
              Configure an identifier scope with a dataset key below to enable matching.
            </Text>
          )}
          {matchRun?.status === 'RUNNING' && (
            <Stack gap={4}>
              <Progress
                value={matchRun.total ? (matchRun.processed / matchRun.total) * 100 : 0}
                animated
              />
              <Text size="sm" c="dimmed">
                Matched {matchRun.processed} of {matchRun.total}…
              </Text>
            </Stack>
          )}
          {matchRun?.status === 'DONE' && (
            <Stack gap={4}>
              <Group gap="xs">
                <Badge color="blue" variant="light">
                  verified {matchRun.verified}
                </Badge>
                <Badge color="green" variant="light">
                  added {matchRun.added}
                </Badge>
                <Badge color="yellow" variant="light">
                  updated {matchRun.updated}
                </Badge>
                <Badge color="red" variant="light">
                  unmatched {matchRun.unmatched}
                </Badge>
              </Group>
              <Text size="sm" c="dimmed">
                Flags appear in the{' '}
                <Anchor component={Link} to={`/projects/${id}/issues`}>
                  Issues
                </Anchor>{' '}
                view.
              </Text>
            </Stack>
          )}
          {matchRun?.status === 'FAILED' && (
            <Alert color="red" title="Match all identifiers failed">
              {matchRun.error ?? 'Unknown error'}
            </Alert>
          )}
        </Stack>
      )}

      {canEdit && (
        <Stack gap="xs">
          <Group justify="space-between">
            <Title order={4} m={0}>
              Merge project
            </Title>
            <Button variant="default" onClick={() => setMerging(true)}>
              Merge…
            </Button>
          </Group>
          <Text size="sm" c="dimmed">
            Merge another project&apos;s names and references into this one.
          </Text>
          <MergeModal opened={merging} onClose={() => setMerging(false)} targetId={id} />
        </Stack>
      )}

      <form
        onSubmit={form.onSubmit((v) => {
          // Blank rows (added via "Add scope" but never filled in) are dropped rather than saved
          // as an empty-scope entry; a blank datasetKey is sent as undefined (-> null on the
          // backend, not matchable) rather than "" so the two states can't drift apart.
          const identifierScopes = (v.identifierScopes ?? [])
            .map((s) => ({ scope: s.scope.trim(), datasetKey: (s.datasetKey ?? '').trim() || undefined }))
            .filter((s) => s.scope !== '');
          mutation.mutate({ ...v, identifierScopes });
        })}
      >
        <fieldset disabled={!canEdit} style={{ border: 'none', padding: 0, margin: 0 }}>
          <Stack gap="md">
            <TextInput label="Title" {...form.getInputProps('title')} />
            <SimpleGrid cols={2}>
              <TextInput label="Alias" {...form.getInputProps('alias')} />
              <Select
                label="Nomenclatural code"
                clearable
                data={NOM_CODES.map((c) => ({ value: c, label: c }))}
                {...form.getInputProps('nomCode')}
              />
            </SimpleGrid>
            <Textarea label="Description" rows={3} {...form.getInputProps('description')} />
            <SimpleGrid cols={2}>
              <Select
                label="License"
                clearable
                data={LICENSES.map((l) => ({ value: l, label: l }))}
                {...form.getInputProps('license')}
              />
            </SimpleGrid>
            <SimpleGrid cols={2}>
              <TextInput label="Geographic scope" {...form.getInputProps('geographicScope')} />
              <TextInput label="Taxonomic scope" {...form.getInputProps('taxonomicScope')} />
            </SimpleGrid>
            <Switch
              label="Show GBIF occurrence layer on maps"
              {...form.getInputProps('gbifOccurrenceLayer', { type: 'checkbox' })}
            />
            <Stack gap="xs">
              <Stack gap={2}>
                <Text size="sm" fw={500}>
                  Identifier scopes (form fields)
                </Text>
                <Text size="xs" c="dimmed">
                  Adds a real identifier field to the taxon Details form for each scope (e.g.
                  ipni). A scope with a CLB dataset key is also eligible for identifier matching.
                </Text>
              </Stack>
              {form.values.identifierScopes?.map((row, index) => (
                <Group key={index} align="flex-end" gap="xs" wrap="nowrap">
                  <Autocomplete
                    aria-label={`Scope ${index + 1}`}
                    placeholder="e.g. ipni"
                    data={idScopesVocab ?? []}
                    style={{ flex: 1 }}
                    {...form.getInputProps(`identifierScopes.${index}.scope`)}
                    onChange={(value) => {
                      form.setFieldValue(`identifierScopes.${index}.scope`, value);
                      // COL conventionally aliases the CLB dataset published under
                      // COL_DATASET_KEY -- default it in once, but only if this row doesn't
                      // already have a dataset key (an existing custom value is never clobbered).
                      if (value.trim().toLowerCase() === 'col' && !row.datasetKey) {
                        form.setFieldValue(`identifierScopes.${index}.datasetKey`, COL_DATASET_KEY);
                      }
                    }}
                  />
                  <TextInput
                    aria-label={`Dataset key ${index + 1}`}
                    placeholder="CLB dataset key"
                    style={{ flex: 1 }}
                    {...form.getInputProps(`identifierScopes.${index}.datasetKey`)}
                  />
                  <ActionIcon
                    type="button"
                    variant="subtle"
                    color="red"
                    aria-label={`Remove scope ${index + 1}`}
                    onClick={() => form.removeListItem('identifierScopes', index)}
                  >
                    <IconTrash size={16} />
                  </ActionIcon>
                </Group>
              ))}
              {form.values.identifierScopes?.some((s) => s.scope.trim().toLowerCase() === 'col') && (
                <Text size="xs" c="dimmed">
                  COL is a CLB project alias for dataset {COL_DATASET_KEY}.
                </Text>
              )}
              <Button
                type="button"
                variant="subtle"
                size="xs"
                leftSection={<IconPlus size={14} />}
                style={{ alignSelf: 'flex-start' }}
                onClick={() => form.insertListItem('identifierScopes', { scope: '', datasetKey: '' })}
              >
                Add scope
              </Button>
            </Stack>
            <Button type="submit" loading={mutation.isPending} disabled={!canEdit}>
              Save
            </Button>
          </Stack>
        </fieldset>
      </form>

      {isOwner && (
        <Stack
          gap="xs"
          style={{
            borderTop: '1px solid var(--mantine-color-red-4)',
            paddingTop: 'var(--mantine-spacing-md)',
          }}
        >
          <Group justify="space-between">
            <Title order={4} m={0} c="red">
              Danger zone
            </Title>
            <Button
              color="red"
              variant="outline"
              leftSection={<IconTrash size={16} />}
              loading={deleteMut.isPending}
              onClick={confirmDelete}
            >
              Delete project
            </Button>
          </Group>
          <Text size="sm" c="dimmed">
            Permanently deletes this project and all of its names, references, and history. This
            cannot be undone.
          </Text>
        </Stack>
      )}
    </Stack>
  );
}
