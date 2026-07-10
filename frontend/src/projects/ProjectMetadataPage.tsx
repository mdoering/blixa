import {
  Alert,
  Anchor,
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
import { notifications } from '@mantine/notifications';
import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { getProject, updateMetadata } from '../api/projects';
import { getColMatchRun, getLatestColMatch, startColMatch } from '../api/col';
import { messageFor } from '../api/client';
import type { UpdateMetadataPayload } from '../api/types';
import { NOM_CODES } from './CreateProjectModal';

const LICENSES = ['CC0-1.0', 'CC-BY-4.0'];
// Poll interval for the bulk "Match all to COL" run while it's RUNNING -- a live but not chatty
// progress indicator over what is, per usage, a sequential CLB round-trip (see
// ColMatchAsyncConfig's single-thread pool on the backend).
const COL_MATCH_POLL_MS = 1500;

export default function ProjectMetadataPage() {
  const { projectId } = useParams();
  const id = Number(projectId);
  const queryClient = useQueryClient();

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
    },
    validate: {
      title: (v) => (v ? null : 'Required'),
    },
  });

  const { data } = useQuery({ queryKey: ['project', id], queryFn: () => getProject(id) });
  const canEdit = data ? ['owner', 'editor'].includes(data.role) : false;

  useEffect(() => {
    if (data) {
      const values = Object.fromEntries(
        Object.entries(data).map(([k, v]) => [k, v ?? undefined]),
      ) as UpdateMetadataPayload;
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

  // Bulk "Match all to COL" job: startColMatch kicks it off and returns the run's id, which then
  // drives a polling GET while the run is still RUNNING (react-query refetchInterval, backend
  // ColMatchRunController). The run id lives in component state rather than the query key alone so
  // a second click restarts polling for the NEW run.
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
      notifications.show({ color: 'red', message: messageFor(e, 'Match all to COL failed to start') }),
  });
  const matchRunning = startMatchMut.isPending || matchRun?.status === 'RUNNING';

  return (
    <Stack style={{ maxWidth: 720 }} gap="xl">
      {canEdit && (
        <Stack gap="xs">
          <Group justify="space-between">
            <Title order={4} m={0}>
              Match all to COL
            </Title>
            <Button
              variant="default"
              loading={startMatchMut.isPending}
              disabled={matchRunning}
              onClick={() => startMatchMut.mutate()}
            >
              Match all to COL
            </Button>
          </Group>
          {matchRun?.status === 'RUNNING' && (
            <Stack gap={4}>
              <Progress
                value={matchRun.total ? (matchRun.processed / matchRun.total) * 100 : 0}
                animated
              />
              <Text size="sm" c="dimmed">
                Matching usage {matchRun.processed} of {matchRun.total}…
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
            <Alert color="red" title="Match all to COL failed">
              {matchRun.error ?? 'Unknown error'}
            </Alert>
          )}
        </Stack>
      )}

      <form onSubmit={form.onSubmit((v) => mutation.mutate(v))}>
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
            <Button type="submit" loading={mutation.isPending} disabled={!canEdit}>
              Save
            </Button>
          </Stack>
        </fieldset>
      </form>
    </Stack>
  );
}
