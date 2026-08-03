import { Group, Loader, Paper, SimpleGrid, Stack, Table, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { getProjectMetrics } from '../api/metrics';

function sum(m: Record<string, number> | undefined): number {
  return Object.values(m ?? {}).reduce((a, b) => a + b, 0);
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <Paper withBorder p="sm" radius="md">
      <Text size="xl" fw={700}>{value.toLocaleString()}</Text>
      <Text size="xs" c="dimmed">{label}</Text>
    </Paper>
  );
}

// Live "now" metrics for a project, shown at the top of the Releases tab: headline counts, an
// accepted/synonym breakdown by rank, and what changed since the last release.
export default function ProjectMetrics({ pid }: { pid: number }) {
  const { data, isLoading } = useQuery({ queryKey: ['metrics', pid], queryFn: () => getProjectMetrics(pid) });

  if (isLoading || !data) return <Loader size="sm" />;

  const accepted = sum(data.acceptedByRank);
  const synonyms = sum(data.synonymsByRank);
  const changes = data.changesSinceLastRelease ?? {};
  const ranks = Object.keys(data.acceptedByRank ?? {});

  return (
    <Stack gap="md">
      <SimpleGrid cols={{ base: 2, sm: 4 }} spacing="sm">
        <Stat label="Accepted" value={accepted} />
        <Stat label="Synonyms" value={synonyms} />
        <Stat label="References" value={data.supplementary?.reference ?? 0} />
        <Stat label="Type material" value={data.supplementary?.typeMaterial ?? 0} />
      </SimpleGrid>

      {ranks.length > 0 && (
        <div>
          <Text fw={600} size="sm" mb={4}>By rank</Text>
          <Table withTableBorder>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Rank</Table.Th>
                <Table.Th ta="right">Accepted</Table.Th>
                <Table.Th ta="right">Synonyms</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {ranks.map((r) => (
                <Table.Tr key={r}>
                  <Table.Td>{r}</Table.Td>
                  <Table.Td ta="right">{(data.acceptedByRank[r] ?? 0).toLocaleString()}</Table.Td>
                  <Table.Td ta="right">{(data.synonymsByRank?.[r] ?? 0).toLocaleString()}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </div>
      )}

      {Object.keys(changes).length > 0 && (
        <div>
          <Text fw={600} size="sm" mb={4}>Changes since last release</Text>
          <Group gap="lg">
            {Object.entries(changes).map(([k, v]) => (
              <Text key={k} size="sm">
                <Text span fw={600}>{Number(v).toLocaleString()}</Text>{' '}
                <Text span c="dimmed">{k}</Text>
              </Text>
            ))}
          </Group>
        </div>
      )}
    </Stack>
  );
}
