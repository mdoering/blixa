import { Alert, Anchor, Badge, Button, Center, Group, Loader, Paper, SimpleGrid, Stack, Table, Text, Title } from '@mantine/core';
import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import { ApiError } from '../api/client';
import { getPublicProject } from '../api/public';
import JoinRequestModal from './JoinRequestModal';

// release.fileSize is bytes -- a friendly human-readable label (mirrors ProjectMetadataPage's
// own formatFileSize; duplicated rather than shared since the public page has no other coupling
// to the editor's project pages).
function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const kb = bytes / 1024;
  if (kb < 1024) return `${kb.toFixed(1)} KB`;
  return `${(kb / 1024).toFixed(1)} MB`;
}

// `metrics` is a free-form JSON blob computed by the backend (ReleaseMetricsService): a project
// with no READY release yet still gets a live-computed object, and older/partial shapes (or no
// metrics at all) must not crash this page -- so every field below is read defensively rather
// than assumed present.
type Metrics = Record<string, unknown>;

function asRankCounts(v: unknown): [string, number][] {
  if (!v || typeof v !== 'object') return [];
  return Object.entries(v as Record<string, unknown>).filter(
    (entry): entry is [string, number] => typeof entry[1] === 'number',
  );
}

interface Contribution {
  userId?: number;
  name?: string | null;
  orcid?: string | null;
  count?: number;
}

function asContributions(v: unknown): Contribution[] {
  return Array.isArray(v) ? v.filter((c): c is Contribution => typeof c === 'object' && c !== null) : [];
}

export default function PublicProjectPage() {
  const { idOrAlias } = useParams<{ idOrAlias: string }>();
  const navigate = useNavigate();
  const [joining, setJoining] = useState(false);

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['publicProject', idOrAlias],
    queryFn: () => getPublicProject(idOrAlias as string),
    enabled: !!idOrAlias,
    retry: false,
  });

  // Alias -> canonical id redirect: only when the URL param isn't already the numeric id (a
  // numeric idOrAlias always resolves to the same id it names, so Number(idOrAlias) !== data.id
  // only ever fires for a non-numeric alias -- the isNumeric check just makes that explicit).
  useEffect(() => {
    if (!data || !idOrAlias) return;
    const isNumeric = /^\d+$/.test(idOrAlias);
    if (!isNumeric && Number(idOrAlias) !== data.id) {
      navigate(`/p/${data.id}`, { replace: true });
    }
  }, [data, idOrAlias, navigate]);

  if (isLoading) {
    return (
      <Center style={{ margin: 48 }}>
        <Loader />
      </Center>
    );
  }

  if (isError) {
    if (error instanceof ApiError && error.status === 404) {
      return <Alert color="gray">This project is not public or does not exist.</Alert>;
    }
    return <Alert color="red">Could not load this project.</Alert>;
  }

  if (!data) return null;

  const metrics: Metrics = (data.metrics ?? {}) as Metrics;
  const accepted = asRankCounts(metrics.acceptedByRank);
  const synonyms = asRankCounts(metrics.synonymsByRank);
  const supplementary = asRankCounts(metrics.supplementary);
  // The backend's metrics blob (ReleaseMetricsService.compute) never actually contains
  // nameUsageCount -- only acceptedByRank/synonymsByRank/supplementary/changesSinceLastRelease/
  // contributions. The real headline count lives on the latest release (releases are returned
  // newest-first), so prefer that and keep the metrics field only as a defensive fallback.
  const headlineNameCount =
    data.releases[0]?.nameUsageCount ??
    (typeof metrics.nameUsageCount === 'number' ? metrics.nameUsageCount : null);
  const changesSinceLastRelease =
    typeof metrics.changesSinceLastRelease === 'number' ? metrics.changesSinceLastRelease : null;
  const contributions = asContributions(metrics.contributions);
  const hasMetadata = data.license || data.nomCode || data.geographicScope || data.taxonomicScope;
  const hasMetrics =
    headlineNameCount != null ||
    accepted.length > 0 ||
    synonyms.length > 0 ||
    supplementary.length > 0 ||
    changesSinceLastRelease != null ||
    contributions.length > 0;

  return (
    <Stack gap="xl">
      <Stack gap={4}>
        <Title order={1}>{data.title}</Title>
        {data.alias && (
          <Text c="dimmed" size="sm">
            {data.alias}
          </Text>
        )}
        {data.description && <Text>{data.description}</Text>}
      </Stack>

      {hasMetadata && (
        <Paper withBorder p="md">
          <Stack gap={4}>
            {data.license && (
              <Text size="sm">
                <b>License:</b> {data.license}
              </Text>
            )}
            {data.nomCode && (
              <Text size="sm">
                <b>Nomenclatural code:</b> {data.nomCode}
              </Text>
            )}
            {data.geographicScope && (
              <Text size="sm">
                <b>Geographic scope:</b> {data.geographicScope}
              </Text>
            )}
            {data.taxonomicScope && (
              <Text size="sm">
                <b>Taxonomic scope:</b> {data.taxonomicScope}
              </Text>
            )}
          </Stack>
        </Paper>
      )}

      <Stack gap="xs">
        <Title order={3}>Contributors</Title>
        {data.contributors.length === 0 ? (
          <Text c="dimmed">No contributors listed.</Text>
        ) : (
          <Stack gap="xs">
            {data.contributors.map((c, i) => (
              <Group key={i} gap="xs">
                <Text fw={500}>{c.name ?? 'Unknown'}</Text>
                {c.orcid && (
                  <Anchor href={`https://orcid.org/${c.orcid}`} target="_blank" rel="noreferrer" size="sm">
                    {c.orcid}
                  </Anchor>
                )}
                <Badge>{c.role}</Badge>
              </Group>
            ))}
          </Stack>
        )}
      </Stack>

      <Paper withBorder p="md">
        <Group justify="space-between" wrap="nowrap" align="center">
          <Stack gap={2}>
            <Text fw={500}>Join this project</Text>
            <Text size="sm" c="dimmed">
              Contribute to this checklist by requesting to join. A project owner reviews each request.
            </Text>
          </Stack>
          <Button onClick={() => setJoining(true)}>Request to join</Button>
        </Group>
      </Paper>
      {idOrAlias && (
        <JoinRequestModal idOrAlias={idOrAlias} opened={joining} onClose={() => setJoining(false)} />
      )}

      {hasMetrics && (
        <Stack gap="xs">
          <Title order={3}>Metrics</Title>
          {headlineNameCount != null && (
            <Text size="lg" fw={600}>
              {headlineNameCount.toLocaleString()} names
            </Text>
          )}
          <SimpleGrid cols={{ base: 1, sm: 2 }}>
            {accepted.length > 0 && (
              <div>
                <Text fw={500} size="sm" mb={4}>
                  Accepted names by rank
                </Text>
                <Group gap={4}>
                  {accepted.map(([rank, count]) => (
                    <Badge key={rank} variant="light">
                      {rank}: {count}
                    </Badge>
                  ))}
                </Group>
              </div>
            )}
            {synonyms.length > 0 && (
              <div>
                <Text fw={500} size="sm" mb={4}>
                  Synonyms by rank
                </Text>
                <Group gap={4}>
                  {synonyms.map(([rank, count]) => (
                    <Badge key={rank} variant="light" color="grape">
                      {rank}: {count}
                    </Badge>
                  ))}
                </Group>
              </div>
            )}
            {supplementary.length > 0 && (
              <div>
                <Text fw={500} size="sm" mb={4}>
                  Supplementary data
                </Text>
                <Group gap={4}>
                  {supplementary.map(([key, count]) => (
                    <Badge key={key} variant="light" color="teal">
                      {key}: {count}
                    </Badge>
                  ))}
                </Group>
              </div>
            )}
          </SimpleGrid>
          {changesSinceLastRelease != null && (
            <Text size="sm" c="dimmed">
              {changesSinceLastRelease} change{changesSinceLastRelease === 1 ? '' : 's'} since last release
            </Text>
          )}
          {contributions.length > 0 && (
            <div>
              <Text fw={500} size="sm" mb={4}>
                Contributions
              </Text>
              <Table>
                <Table.Tbody>
                  {contributions.map((c, i) => (
                    <Table.Tr key={c.userId ?? i}>
                      <Table.Td>{c.name ?? 'Unknown'}</Table.Td>
                      <Table.Td>{c.count ?? 0}</Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </div>
          )}
        </Stack>
      )}

      <Stack gap="xs">
        <Title order={3}>Releases</Title>
        {data.releases.length === 0 ? (
          <Text c="dimmed">No releases yet.</Text>
        ) : (
          <Table striped>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Version</Table.Th>
                <Table.Th>Date</Table.Th>
                <Table.Th>Size</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {data.releases.map((r) => (
                <Table.Tr key={r.id}>
                  <Table.Td>{r.version}</Table.Td>
                  <Table.Td>{r.createdAt ? new Date(r.createdAt).toLocaleDateString() : ''}</Table.Td>
                  <Table.Td>{r.fileSize != null ? formatFileSize(r.fileSize) : ''}</Table.Td>
                  <Table.Td>
                    <Anchor href={r.downloadUrl} download>
                      Download
                    </Anchor>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </Stack>
    </Stack>
  );
}
