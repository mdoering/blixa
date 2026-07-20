import { Button, Group, Modal, Radio, SegmentedControl, Stack, Text } from '@mantine/core';
import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getChildren } from '../api/tree';
import { searchUsages, type DeleteMode } from '../api/usages';
import EntitySelect from '../child/EntitySelect';

interface Props {
  pid: number;
  usage: { id: number; scientificName: string | null; status: string | null };
  opened: boolean;
  deleting?: boolean;
  onClose: () => void;
  onConfirm: (mode: DeleteMode, reparentTo: number | null) => void;
}

// Choose how much to delete (focal only / with synonyms / entire subtree) and, when the focal has
// accepted children, where those children go (its parent by default, or a searched-for new parent).
export default function DeleteNameModal({ pid, usage, opened, deleting, onClose, onConfirm }: Props) {
  const isAccepted = (usage.status ?? '').toUpperCase() === 'ACCEPTED';
  const { data: children } = useQuery({
    queryKey: ['children', pid, usage.id],
    queryFn: () => getChildren(pid, usage.id),
    enabled: opened && isAccepted,
  });
  const hasChildren = (children ?? []).length > 0;

  const [mode, setMode] = useState<DeleteMode>('FOCAL_ONLY');
  const [reparentMode, setReparentMode] = useState<'grandparent' | 'choose'>('grandparent');
  const [reparentTo, setReparentTo] = useState<string | null>(null);

  useEffect(() => {
    if (opened) {
      setMode('FOCAL_ONLY');
      setReparentMode('grandparent');
      setReparentTo(null);
    }
  }, [opened]);

  const showReparent = hasChildren && mode !== 'SUBTREE';
  const effectiveReparentTo =
    showReparent && reparentMode === 'choose' && reparentTo ? Number(reparentTo) : null;
  const blocked = showReparent && reparentMode === 'choose' && !reparentTo;

  return (
    <Modal opened={opened} onClose={onClose} title="Delete name" size="md">
      <Stack>
        <Text size="sm">
          Delete “{usage.scientificName ?? 'this name'}”? This can't be undone.
        </Text>

        <Radio.Group value={mode} onChange={(v) => setMode(v as DeleteMode)}>
          <Stack gap="xs">
            <Radio value="FOCAL_ONLY" label="This taxon only" />
            {isAccepted && <Radio value="WITH_SYNONYMS" label="This taxon and its synonyms" />}
            {isAccepted && (
              <Radio
                value="SUBTREE"
                label="Entire subtree (this taxon, its descendants, and their synonyms)"
              />
            )}
          </Stack>
        </Radio.Group>

        {showReparent && (
          <Stack gap="xs">
            <Text size="sm">This taxon has accepted children. Move them to:</Text>
            <SegmentedControl
              value={reparentMode}
              onChange={(v) => setReparentMode(v as 'grandparent' | 'choose')}
              data={[
                { value: 'grandparent', label: 'Its parent (default)' },
                { value: 'choose', label: 'Another taxon…' },
              ]}
            />
            {reparentMode === 'choose' && (
              <EntitySelect
                value={reparentTo}
                onChange={setReparentTo}
                queryKey={['usagesPicker', pid]}
                placeholder="Search for a new parent…"
                load={() =>
                  searchUsages(pid, { limit: 500, offset: 0 }).then((page) =>
                    page.items
                      .filter((u) => u.id !== usage.id)
                      .map((u) => ({ value: String(u.id), label: u.scientificName ?? `#${u.id}` })),
                  )
                }
              />
            )}
          </Stack>
        )}

        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            Cancel
          </Button>
          <Button
            color="red"
            loading={deleting}
            disabled={blocked}
            onClick={() => onConfirm(mode, effectiveReparentTo)}
          >
            Delete
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
