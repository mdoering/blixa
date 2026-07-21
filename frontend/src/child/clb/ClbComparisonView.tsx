import { Anchor, Badge, Group, Stack, Table, Text } from '@mantine/core';
import type { ClbComparison } from '../../api/clb';
import DatasetLabel from '../../clb/DatasetLabel';

// The "editor" side of the comparison, shaped to line up with ClbComparison.
export interface OursSide {
  scientificName: string | null;
  authorship: string | null;
  rank: string | null;
  status: string | null;
  classification: { rank: string | null; name: string | null }[];
  synonyms: { scientificName: string | null; authorship: string | null; status: string | null }[];
}

const norm = (s: string | null | undefined) => (s ?? '').trim().toLowerCase();
const same = (a: string | null | undefined, b: string | null | undefined) => norm(a) === norm(b);

function Cell({ value, differs }: { value: string | null | undefined; differs: boolean }) {
  const shown = value ?? '—';
  return differs ? (
    <Text component="span" fw={600} c="orange.7">
      {shown}
    </Text>
  ) : (
    <Text component="span">{shown}</Text>
  );
}

function rankMap(items: { rank: string | null; name: string | null }[]): Map<string, string> {
  const m = new Map<string, string>();
  for (const it of items) if (it.rank) m.set(norm(it.rank), it.name ?? '');
  return m;
}

// Side-by-side comparison of the focal taxon (ours) vs a CLB taxon, with differing values highlighted.
export default function ClbComparisonView({ ours, clb }: { ours: OursSide; clb: ClbComparison }) {
  const clbByRank = rankMap(clb.classification);
  const oursByRank = rankMap(ours.classification);

  const oursSynNames = new Set(ours.synonyms.map((s) => norm(s.scientificName)));
  const clbSynNames = new Set(clb.synonyms.map((s) => norm(s.scientificName)));

  const scalar = (label: string, a: string | null, b: string | null) => (
    <Table.Tr>
      <Table.Th w={130}>{label}</Table.Th>
      <Table.Td>
        <Cell value={a} differs={!same(a, b)} />
      </Table.Td>
      <Table.Td>
        <Cell value={b} differs={!same(a, b)} />
      </Table.Td>
    </Table.Tr>
  );

  const classificationCell = (
    items: { rank: string | null; name: string | null }[],
    other: Map<string, string>,
  ) => (
    <Stack gap={2}>
      {items.length === 0 && <Text c="dimmed" size="sm">—</Text>}
      {items.map((it, i) => {
        const differs = it.rank ? !same(other.get(norm(it.rank)) ?? null, it.name) : false;
        return (
          <Text key={`${it.rank}-${i}`} size="sm" c={differs ? 'orange.7' : undefined} fw={differs ? 600 : undefined}>
            {it.rank ? `${it.rank}: ` : ''}
            {it.name ?? '—'}
          </Text>
        );
      })}
    </Stack>
  );

  const synonymsCell = (
    items: { scientificName: string | null; authorship: string | null; status: string | null }[],
    otherSet: Set<string>,
  ) => (
    <Stack gap={2}>
      {items.length === 0 && <Text c="dimmed" size="sm">—</Text>}
      {items.map((s, i) => {
        const onlyHere = !otherSet.has(norm(s.scientificName));
        return (
          <Group key={`${s.scientificName}-${i}`} gap={6} wrap="nowrap">
            <Text size="sm">
              {s.scientificName} {s.authorship ?? ''}
            </Text>
            {onlyHere && (
              <Badge size="xs" color="orange" variant="light">
                only here
              </Badge>
            )}
          </Group>
        );
      })}
    </Stack>
  );

  return (
    <Table withRowBorders verticalSpacing="xs">
      <Table.Thead>
        <Table.Tr>
          <Table.Th />
          <Table.Th>Ours</Table.Th>
          <Table.Th>
            CLB{' '}
            <Anchor href={clb.link} target="_blank" rel="noopener noreferrer" size="sm">
              <DatasetLabel datasetKey={clb.datasetKey} /> ↗
            </Anchor>
          </Table.Th>
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {scalar('Name', ours.scientificName, clb.scientificName)}
        {scalar('Authorship', ours.authorship, clb.authorship)}
        {scalar('Rank', ours.rank, clb.rank)}
        {scalar('Status', ours.status, clb.status)}
        <Table.Tr>
          <Table.Th>Classification</Table.Th>
          <Table.Td>{classificationCell(ours.classification, clbByRank)}</Table.Td>
          <Table.Td>{classificationCell(clb.classification, oursByRank)}</Table.Td>
        </Table.Tr>
        <Table.Tr>
          <Table.Th>Synonyms</Table.Th>
          <Table.Td>{synonymsCell(ours.synonyms, clbSynNames)}</Table.Td>
          <Table.Td>{synonymsCell(clb.synonyms, oursSynNames)}</Table.Td>
        </Table.Tr>
      </Table.Tbody>
    </Table>
  );
}
