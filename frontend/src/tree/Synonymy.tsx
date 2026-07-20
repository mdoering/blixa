import { Box, Button, Group, List, Stack, Text } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { useQuery } from '@tanstack/react-query';
import { getSynonymy, type SynEntry } from '../api/usages';
import HomotypicGroupModal from './HomotypicGroupModal';

function EntryLine({ e, marker }: { e: SynEntry; marker: '≡' | '=' }) {
  return (
    <Group gap={6} wrap="nowrap" align="baseline">
      <Text span c="dimmed" w={12} ta="center">{marker}</Text>
      <span>
        {e.formattedName ?? e.scientificName}
        {!e.formattedName && e.authorship ? (
          <Text span c="dimmed" size="xs"> {e.authorship}</Text>
        ) : null}
      </span>
    </Group>
  );
}

export interface SynonymyProps {
  pid: number;
  usageId: number;
  canEdit?: boolean;
}

// Nested synonymy of an ACCEPTED usage (see backend name/homotypy). Recombinations homotypic to the
// accepted name render first with ≡; each heterotypic group renders its basionym with = and its
// recombinations indented with ≡; misapplied names come last. `Group synonyms` (editor) opens the
// detect/confirm modal.
export default function Synonymy({ pid, usageId, canEdit = false }: SynonymyProps) {
  const { data, isLoading } = useQuery({
    queryKey: ['synonymy', pid, usageId],
    queryFn: () => getSynonymy(pid, usageId),
  });
  const [opened, { open, close }] = useDisclosure(false);

  if (isLoading) return <Text size="sm" c="dimmed">Loading…</Text>;
  const s = data;
  const empty =
    !s || (s.homotypic.length === 0 && s.heterotypicGroups.length === 0 && s.misapplied.length === 0);

  return (
    <Stack gap="sm">
      {canEdit && (
        <Group justify="flex-end">
          <Button size="xs" variant="light" onClick={open}>Group synonyms</Button>
        </Group>
      )}
      {empty && <Text size="sm" c="dimmed">No synonyms</Text>}
      {s && s.homotypic.length > 0 && (
        <List listStyleType="none" spacing={2}>
          {s.homotypic.map((e) => (
            <List.Item key={e.id}><EntryLine e={e} marker="≡" /></List.Item>
          ))}
        </List>
      )}
      {s &&
        s.heterotypicGroups.map((grp, i) => (
          <List listStyleType="none" spacing={2} key={grp[0]?.id ?? i}>
            {grp.map((e, idx) => (
              <List.Item key={e.id}>
                <Box pl={idx === 0 ? 0 : 'md'}>
                  <EntryLine e={e} marker={idx === 0 ? '=' : '≡'} />
                </Box>
              </List.Item>
            ))}
          </List>
        ))}
      {s && s.misapplied.length > 0 && (
        <List listStyleType="none" spacing={2}>
          {s.misapplied.map((e) => (
            <List.Item key={e.id}><EntryLine e={e} marker="=" /></List.Item>
          ))}
        </List>
      )}
      {opened && (
        <HomotypicGroupModal pid={pid} usageId={usageId} onClose={close} />
      )}
    </Stack>
  );
}
