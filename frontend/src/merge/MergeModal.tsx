import {
  Alert,
  Anchor,
  Badge,
  Button,
  Group,
  Loader,
  Modal,
  Select,
  SegmentedControl,
  Stack,
  Switch,
  Text,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import {
  applyMerge,
  getLatestMerge,
  getMergeRun,
  startMerge,
  type MergeMetrics,
  type MergeMode,
} from '../api/merge';
import { listProjects } from '../api/projects';
import { messageFor } from '../api/client';
import MergeMappingTables from './MergeMappingTables';

export interface MergeModalProps {
  opened: boolean;
  onClose: () => void;
  targetId: number;
}

// Poll interval while the run is RUNNING (computing the plan) or APPLYING (writing it) -- same
// shape/cadence as the col-match/export/import polls elsewhere (see ProjectMetadataPage's
// COL_MATCH_POLL_MS/EXPORT_POLL_MS, ImportProjectModal's IMPORT_POLL_MS).
const MERGE_POLL_MS = 1500;

// A plan this big is applied batch-committed (non-transactional) by default -- matches the spirit
// of the backend's own apply-batch chunking (coldp.merge.apply-batch, default 500 rows per commit);
// this is just the frontend's up-front hint/default, not a hard rule the backend enforces.
const LARGE_PLAN_THRESHOLD = 5000;

function totalCandidates(m: MergeMetrics): number {
  return (
    m.names.new +
    m.names.matched +
    m.names.possibleHomonym +
    m.names.possibleFuzzy +
    m.references.new +
    m.references.matched +
    m.references.possible
  );
}

// Mirrors MergeApplyService.isFullImport: every candidate (both halves of the plan) is NEW -- no
// MATCHED/POSSIBLE_* at all. The backend forces transactional=false for this case regardless of
// what's sent, so the frontend locks the Switch off to match rather than offer a choice the apply
// will silently override.
function isFullImport(m: MergeMetrics): boolean {
  return (
    m.names.matched === 0 &&
    m.names.possibleHomonym === 0 &&
    m.names.possibleFuzzy === 0 &&
    m.references.matched === 0 &&
    m.references.possible === 0
  );
}

const MODE_OPTIONS: { label: string; value: MergeMode }[] = [
  { label: 'Overwrite', value: 'OVERWRITE' },
  { label: 'Fill gaps', value: 'FILL_GAPS' },
  { label: 'New only', value: 'NEW_ONLY' },
];

// Supervised project merge: pick a source project, start the compute-plan job, poll it to PLANNED,
// review the impact metrics, optionally open the per-row mapping review (Names/References tabs
// with overrides, Task 10), pick a mode + transaction option, and apply -- poll again to DONE.
export default function MergeModal({ opened, onClose, targetId }: MergeModalProps) {
  const queryClient = useQueryClient();

  const [sourceId, setSourceId] = useState<string | null>(null);
  const [runId, setRunId] = useState<number | null>(null);
  // FILL_GAPS (never overwrites an existing curated value, still fills blanks + adds relations) is
  // the safe default -- OVERWRITE (source clobbers target) is the most destructive option and
  // shouldn't be what a curator applies by just not touching the control.
  const [mode, setMode] = useState<MergeMode>('FILL_GAPS');
  const [transactional, setTransactional] = useState(true);
  const [showMapping, setShowMapping] = useState(false);

  // Resets local UI state on close so a later reopen starts clean; runId itself is re-seeded from
  // getLatestMerge below (seededLatest guard), not reset here, so a reopen while a run from an
  // earlier open is still in flight (or just finished) resumes it rather than losing track of it.
  useEffect(() => {
    if (!opened) {
      setSourceId(null);
      setMode('FILL_GAPS');
      setTransactional(true);
      setShowMapping(false);
    }
  }, [opened]);

  const { data: projects } = useQuery({
    queryKey: ['projects'],
    queryFn: listProjects,
    enabled: opened,
  });
  const sourceOptions = (projects ?? [])
    .filter((p) => p.id !== targetId)
    .map((p) => ({ value: String(p.id), label: p.title }));

  // Latest-run view: on open, look up the most recent merge run for this target (if any) so
  // reopening the modal resumes a still-RUNNING/PLANNED/APPLYING run's state, or the last run's
  // summary, without re-starting. Seeded into runId exactly once per open (seededLatest guard),
  // same race-avoidance reasoning as ProjectMetadataPage's seededLatest/seededLatestExport.
  const { data: latestRun, isSuccess: latestLoaded } = useQuery({
    queryKey: ['mergeLatest', targetId],
    queryFn: () => getLatestMerge(targetId),
    enabled: opened,
  });
  const seededLatest = useRef(false);
  useEffect(() => {
    if (opened && latestLoaded && !seededLatest.current) {
      seededLatest.current = true;
      if (latestRun) setRunId((current) => current ?? latestRun.id);
    }
    if (!opened) {
      seededLatest.current = false;
      setRunId(null);
    }
  }, [opened, latestLoaded, latestRun]);

  const { data: run } = useQuery({
    queryKey: ['mergeRun', targetId, runId],
    queryFn: () => getMergeRun(targetId, runId as number),
    enabled: opened && runId != null,
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return status === 'RUNNING' || status === 'APPLYING' ? MERGE_POLL_MS : false;
    },
  });

  // Once a PLANNED run's metrics arrive, default the transaction Switch: off (+ locked) for a
  // full-import plan (the backend forces this anyway), off (but still togglable) for a large plan,
  // on otherwise. Keyed on run id/status (not the derived booleans) so this only re-fires when a
  // run actually reaches PLANNED, never on a user's own subsequent toggle.
  useEffect(() => {
    if (run?.status === 'PLANNED' && run.metrics) {
      setTransactional(!(isFullImport(run.metrics) || totalCandidates(run.metrics) > LARGE_PLAN_THRESHOLD));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [run?.id, run?.status]);

  const startMut = useMutation({
    mutationFn: () => startMerge(targetId, Number(sourceId)),
    onSuccess: (started) => setRunId(started.id),
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Merge failed to start') }),
  });

  const applyMut = useMutation({
    mutationFn: () => applyMerge(targetId, runId as number, { mode, transactional }),
    onSuccess: (updated) => {
      // The apply POST (not the polled GET) is what actually flips the run to APPLYING -- seed the
      // poll query's cache with the response so refetchInterval (keyed on query.state.data.status)
      // resumes polling immediately rather than waiting for a GET that was never triggered.
      queryClient.setQueryData(['mergeRun', targetId, runId], updated);
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Apply failed to start') }),
  });

  function startNewMerge() {
    setRunId(null);
    setSourceId(null);
    setMode('FILL_GAPS');
    setTransactional(true);
    setShowMapping(false);
  }

  const metrics = run?.status === 'PLANNED' ? run.metrics : null;
  const fullImport = metrics != null && isFullImport(metrics);
  const largePlan = metrics != null && totalCandidates(metrics) > LARGE_PLAN_THRESHOLD;
  const possibleCount = metrics
    ? metrics.names.possibleHomonym + metrics.names.possibleFuzzy + metrics.references.possible
    : 0;

  return (
    <Modal opened={opened} onClose={onClose} title="Merge project" size="lg">
      <Stack gap="md">
        {runId == null && (
          <Stack gap="xs">
            <Select
              label="Source project"
              placeholder="Pick a project to merge from"
              data={sourceOptions}
              value={sourceId}
              onChange={setSourceId}
              searchable
            />
            <Group justify="flex-end">
              <Button variant="default" onClick={onClose}>
                Cancel
              </Button>
              <Button
                onClick={() => startMut.mutate()}
                loading={startMut.isPending}
                disabled={!sourceId}
              >
                Start merge
              </Button>
            </Group>
          </Stack>
        )}

        {run?.status === 'RUNNING' && (
          <Group gap="xs">
            <Loader size="sm" />
            <Text size="sm" c="dimmed">
              Computing merge plan…
            </Text>
          </Group>
        )}

        {run?.status === 'PLANNED' && metrics && (
          <Stack gap="md">
            <Stack gap="xs">
              <Title order={5} m={0}>
                Impact
              </Title>
              <Group gap="xs">
                <Text size="sm" fw={500} w={80}>
                  Names
                </Text>
                <Badge color="green" variant="light">
                  new {metrics.names.new}
                </Badge>
                <Badge color="blue" variant="light">
                  matched {metrics.names.matched}
                </Badge>
                <Badge color="yellow" variant="light">
                  possible homonym {metrics.names.possibleHomonym}
                </Badge>
                <Badge color="orange" variant="light">
                  possible fuzzy {metrics.names.possibleFuzzy}
                </Badge>
              </Group>
              <Group gap="xs">
                <Text size="sm" fw={500} w={80}>
                  References
                </Text>
                <Badge color="green" variant="light">
                  new {metrics.references.new}
                </Badge>
                <Badge color="blue" variant="light">
                  matched {metrics.references.matched}
                </Badge>
                <Badge color="yellow" variant="light">
                  possible {metrics.references.possible}
                </Badge>
              </Group>
              <Group gap="xs">
                <Badge color="teal" variant="light">
                  new accepted {metrics.newAccepted}
                </Badge>
                <Badge color="grape" variant="light">
                  new synonyms {metrics.newSynonyms}
                </Badge>
                <Badge color="red" variant="light">
                  unanchored {metrics.unanchored}
                </Badge>
              </Group>
              <Button
                variant="default"
                size="xs"
                onClick={() => setShowMapping((v) => !v)}
                style={{ alignSelf: 'flex-start' }}
              >
                {showMapping ? 'Hide mapping' : 'Review mapping'}
              </Button>
            </Stack>

            {showMapping && runId != null && (
              <MergeMappingTables targetId={targetId} runId={runId} />
            )}

            {possibleCount > 0 && (
              <Alert color="yellow" title="Unreviewed possible matches">
                {possibleCount} possible {possibleCount === 1 ? 'match is' : 'matches are'}{' '}
                unreviewed — they will be added as NEW. Review the mapping first?
              </Alert>
            )}

            <Stack gap="xs">
              <Text size="sm" fw={500}>
                Mode
              </Text>
              <SegmentedControl
                value={mode}
                onChange={(v) => setMode(v as MergeMode)}
                data={MODE_OPTIONS}
              />
              <Switch
                label="Run in one transaction"
                checked={transactional}
                disabled={fullImport}
                onChange={(e) => setTransactional(e.currentTarget.checked)}
              />
              {fullImport && (
                <Text size="xs" c="dimmed">
                  full import — no transaction needed
                </Text>
              )}
              {!fullImport && largePlan && (
                <Text size="xs" c="dimmed">
                  large plan: applying without a single transaction (batch-committed, re-runnable)
                </Text>
              )}
            </Stack>

            <Group justify="flex-end">
              <Button variant="default" onClick={onClose}>
                Close
              </Button>
              <Button onClick={() => applyMut.mutate()} loading={applyMut.isPending}>
                Apply merge
              </Button>
            </Group>
          </Stack>
        )}

        {run?.status === 'APPLYING' && (
          <Group gap="xs">
            <Loader size="sm" />
            <Text size="sm" c="dimmed">
              Applying…
            </Text>
          </Group>
        )}

        {run?.status === 'DONE' && (
          <Stack gap="xs">
            <Text size="sm">Merge complete.</Text>
            <Badge color={run.issues && run.issues.length > 0 ? 'yellow' : 'green'} variant="light">
              {run.issues?.length ?? 0} issue{(run.issues?.length ?? 0) === 1 ? '' : 's'}
            </Badge>
            <Anchor component={Link} to={`/projects/${targetId}`} onClick={onClose}>
              Open target project
            </Anchor>
            <Group justify="flex-end">
              <Button variant="default" onClick={startNewMerge}>
                Start a new merge
              </Button>
              <Button onClick={onClose}>Close</Button>
            </Group>
          </Stack>
        )}

        {run?.status === 'FAILED' && (
          <Stack gap="xs">
            <Alert color="red" title="Merge failed">
              {run.error ?? 'Unknown error'}
            </Alert>
            <Group justify="flex-end">
              <Button variant="default" onClick={startNewMerge}>
                Start a new merge
              </Button>
              <Button onClick={onClose}>Close</Button>
            </Group>
          </Stack>
        )}
      </Stack>
    </Modal>
  );
}
