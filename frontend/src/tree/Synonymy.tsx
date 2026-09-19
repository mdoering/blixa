import { ActionIcon, Anchor, Box, Group, List, Menu, Stack, Text } from '@mantine/core';
import { IconDots, IconGitMerge, IconPlus } from '@tabler/icons-react';
import { useDisclosure } from '@mantine/hooks';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { getSynonymy, usageCountsKey, type SynEntry } from '../api/usages';
import HomotypicGroupModal from './HomotypicGroupModal';
import BulkAddModal from '../names/BulkAddModal';

// The name links to the synonym's own editor (the /names panel opens any usage via its `usage`
// param) -- the accepted-only tree can't reach a synonym, so this is how a synonym's nomenclature +
// type material get edited, one click from where it's listed under its accepted name.
function EntryLine({ pid, e, marker }: { pid: number; e: SynEntry; marker: '≡' | '=' }) {
  return (
    <Group gap={6} wrap="nowrap" align="baseline">
      <Text span c="dimmed" w={12} ta="center">{marker}</Text>
      <span>
        <Anchor component={Link} to={`/projects/${pid}/names?usage=${e.id}`} size="sm">
          {e.formattedName ?? e.scientificName}
        </Anchor>
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
  // the accepted usage's name, shown as the bulk-add target's heading
  acceptedName?: string | null;
}

// Nested synonymy of an ACCEPTED usage (see backend name/homotypy). Recombinations homotypic to the
// accepted name render first with ≡; each heterotypic group renders its basionym with = and its
// recombinations indented with ≡; misapplied names come last. `Group synonyms` (editor) opens the
// detect/confirm modal.
export default function Synonymy({ pid, usageId, canEdit = false, acceptedName }: SynonymyProps) {
  const queryClient = useQueryClient();
  const { data, isLoading } = useQuery({
    queryKey: ['synonymy', pid, usageId],
    queryFn: () => getSynonymy(pid, usageId),
  });
  const [opened, { open, close }] = useDisclosure(false);
  const [bulkOpen, { open: openBulk, close: closeBulk }] = useDisclosure(false);

  if (isLoading) return <Text size="sm" c="dimmed">Loading…</Text>;
  const s = data;
  const empty =
    !s || (s.homotypic.length === 0 && s.heterotypicGroups.length === 0 && s.misapplied.length === 0);

  return (
    <Stack gap="sm">
      {canEdit && (
        <Group justify="flex-end">
          <Menu position="bottom-end" withinPortal>
            <Menu.Target>
              <ActionIcon variant="subtle" color="gray" aria-label="Synonym actions">
                <IconDots size={16} />
              </ActionIcon>
            </Menu.Target>
            <Menu.Dropdown>
              <Menu.Item leftSection={<IconPlus size={14} />} onClick={openBulk}>
                Add synonyms…
              </Menu.Item>
              <Menu.Item leftSection={<IconGitMerge size={14} />} onClick={open}>
                Group synonyms
              </Menu.Item>
            </Menu.Dropdown>
          </Menu>
        </Group>
      )}
      {empty && <Text size="sm" c="dimmed">No synonyms</Text>}
      {s && s.homotypic.length > 0 && (
        <List listStyleType="none" spacing={2}>
          {s.homotypic.map((e) => (
            <List.Item key={e.id}><EntryLine pid={pid} e={e} marker="≡" /></List.Item>
          ))}
        </List>
      )}
      {s &&
        s.heterotypicGroups.map((grp, i) => (
          <List listStyleType="none" spacing={2} key={grp[0]?.id ?? i}>
            {grp.map((e, idx) => (
              <List.Item key={e.id}>
                <Box pl={idx === 0 ? 0 : 'md'}>
                  <EntryLine pid={pid} e={e} marker={idx === 0 ? '=' : '≡'} />
                </Box>
              </List.Item>
            ))}
          </List>
        ))}
      {s && s.misapplied.length > 0 && (
        <List listStyleType="none" spacing={2}>
          {s.misapplied.map((e) => (
            <List.Item key={e.id}><EntryLine pid={pid} e={e} marker="=" /></List.Item>
          ))}
        </List>
      )}
      {opened && (
        <HomotypicGroupModal pid={pid} usageId={usageId} onClose={close} />
      )}
      <BulkAddModal
        pid={pid}
        target={{ id: usageId, scientificName: acceptedName ?? null }}
        opened={bulkOpen}
        fixedMode="synonyms"
        onClose={closeBulk}
        onDone={() => {
          queryClient.invalidateQueries({ queryKey: ['synonymy', pid, usageId] });
          queryClient.invalidateQueries({ queryKey: usageCountsKey(pid, usageId) });
        }}
      />
    </Stack>
  );
}
