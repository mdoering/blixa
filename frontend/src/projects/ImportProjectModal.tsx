import {
  Alert,
  Anchor,
  Autocomplete,
  Badge,
  Button,
  FileInput,
  Group,
  List,
  Loader,
  Modal,
  Stack,
  Switch,
  Text,
  TextInput,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { getIdScopes } from '../api/coldp';
import { getImportRun, startImport } from '../api/import';
import { messageFor } from '../api/client';

export interface ImportProjectModalProps {
  opened: boolean;
  onClose: () => void;
}

// Poll interval for the import run while it's RUNNING -- same shape/cadence as the export and
// col-match polls on ProjectMetadataPage (see COL_MATCH_POLL_MS/EXPORT_POLL_MS there).
const IMPORT_POLL_MS = 1500;

// Upload a ColDP .zip or a text-tree file (.txtree/.tree/.txt/.tsv) -- the backend detects the
// format from the filename and parses it into a brand-new project (async job) -> poll until
// DONE/FAILED. Unlike export/col-match (which act on an existing project this modal isn't scoped
// to one), so there's no "resume the latest run on mount" seeding here -- each open starts fresh.
export default function ImportProjectModal({ opened, onClose }: ImportProjectModalProps) {
  const queryClient = useQueryClient();
  const [file, setFile] = useState<File | null>(null);
  const [title, setTitle] = useState('');
  const [preserveIds, setPreserveIds] = useState(false);
  const [idScope, setIdScope] = useState('');
  const [runId, setRunId] = useState<number | null>(null);

  // preserveIds/idScope only make sense for a ColDP .zip -- the backend forces them off for a
  // text-tree upload, so the fields are hidden entirely rather than merely disabled.
  const isTxtTree = !!file && /\.(txtree|tree|txt|tsv)$/i.test(file.name);

  // Suggestions for the scope Autocomplete -- same vocab + free-text-custom-entry pattern as the
  // Project settings identifier-scopes row editor (ProjectMetadataPage).
  const { data: idScopesVocab } = useQuery({ queryKey: ['idScopes'], queryFn: getIdScopes });

  useEffect(() => {
    if (opened) {
      setFile(null);
      setTitle('');
      setPreserveIds(false);
      setIdScope('');
      setRunId(null);
    }
  }, [opened]);

  const { data: run } = useQuery({
    queryKey: ['importRun', runId],
    queryFn: () => getImportRun(runId as number),
    enabled: runId != null,
    refetchInterval: (query) => (query.state.data?.status === 'RUNNING' ? IMPORT_POLL_MS : false),
  });

  // Once the run lands on DONE, the new project is real -- refresh the projects list so it shows
  // up without the user having to manually reload.
  useEffect(() => {
    if (run?.status === 'DONE') {
      queryClient.invalidateQueries({ queryKey: ['projects'] });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [run?.status, run?.id]);

  const mutation = useMutation({
    mutationFn: () =>
      startImport(
        file as File,
        isTxtTree ? false : preserveIds,
        isTxtTree ? undefined : preserveIds ? idScope.trim() : undefined,
        title,
      ),
    onSuccess: (started) => setRunId(started.id),
    onError: (e) =>
      notifications.show({ color: 'red', message: messageFor(e, 'Import failed to start') }),
  });

  const running = mutation.isPending || run?.status === 'RUNNING';
  const scopeMissing = !isTxtTree && preserveIds && idScope.trim() === '';
  const canSubmit = file != null && !scopeMissing && !running;

  return (
    <Modal opened={opened} onClose={onClose} title="Import ColDP">
      <Stack gap="md">
        <FileInput
          label="ColDP or text-tree file"
          placeholder="Select a .zip or text-tree file"
          accept=".zip,.txtree,.tree,.txt,.tsv"
          value={file}
          onChange={setFile}
          clearable
        />
        <TextInput
          label="Title"
          description="Used as the project title for text-tree imports."
          value={title}
          onChange={(e) => setTitle(e.currentTarget.value)}
        />
        {!isTxtTree && (
          <Switch
            label="Preserve source identifiers"
            checked={preserveIds}
            onChange={(e) => setPreserveIds(e.currentTarget.checked)}
          />
        )}
        {!isTxtTree && preserveIds && (
          <Autocomplete
            label="Identifier scope"
            placeholder="e.g. col"
            data={(idScopesVocab ?? []).map((s) => s.scope)}
            value={idScope}
            onChange={setIdScope}
            error={scopeMissing ? 'Required' : null}
          />
        )}

        {run?.status === 'RUNNING' && (
          <Group gap="xs">
            <Loader size="sm" />
            <Text size="sm" c="dimmed">
              Importing…
            </Text>
          </Group>
        )}

        {run?.status === 'DONE' && (
          <Stack gap={4}>
            <Group gap="xs">
              <Badge color="blue" variant="light">
                usages {run.nameUsageCount}
              </Badge>
              <Badge color="grape" variant="light">
                references {run.referenceCount}
              </Badge>
              <Badge color="teal" variant="light">
                authors {run.authorCount}
              </Badge>
            </Group>
            {run.projectId != null && (
              <Anchor component={Link} to={`/projects/${run.projectId}`}>
                Open imported project
              </Anchor>
            )}
            {run.issues.length > 0 && (
              <Stack gap={2}>
                <Text size="sm" c="dimmed">
                  {run.issues.length} issue{run.issues.length === 1 ? '' : 's'}
                </Text>
                <List size="sm">
                  {run.issues.map((issue, i) => (
                    <List.Item key={i}>
                      {issue.entity}: {issue.message}
                    </List.Item>
                  ))}
                </List>
              </Stack>
            )}
          </Stack>
        )}

        {run?.status === 'FAILED' && (
          <Alert color="red" title="Import failed">
            {run.error ?? 'Unknown error'}
          </Alert>
        )}

        <Group justify="flex-end">
          {run?.status === 'DONE' ? (
            // The import already created the project -- offering "Import" again here just makes a
            // second, duplicate project (and "Cancel" no longer means anything), so once we're DONE
            // the only action is to close the report.
            <Button onClick={onClose}>Done</Button>
          ) : (
            <>
              <Button variant="default" onClick={onClose}>
                Cancel
              </Button>
              <Button
                onClick={() => mutation.mutate()}
                loading={mutation.isPending}
                disabled={!canSubmit}
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
