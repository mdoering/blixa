import { ActionIcon, Button, Group, Stack, Text, TextInput } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconTrash } from '@tabler/icons-react';
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { searchClbDatasets } from '../api/clb';
import DatasetLabel from '../clb/DatasetLabel';

export interface FavoriteRow {
  key: string;
  title: string | null;
}

// Manage the project's favorite ChecklistBank datasets (quick picks in the compare-with-CLB flow):
// search CLB datasets to add, or remove existing ones. Controlled; the parent form persists the list.
export default function FavoriteClbDatasetsEditor({
  value,
  onChange,
}: {
  value: FavoriteRow[];
  onChange: (v: FavoriteRow[]) => void;
}) {
  const [q, setQ] = useState('');
  const [debouncedQ] = useDebouncedValue(q, 300);
  const { data: hits } = useQuery({
    queryKey: ['clbDatasets', debouncedQ],
    queryFn: () => searchClbDatasets(debouncedQ),
    enabled: debouncedQ.trim().length > 0,
  });
  const keys = new Set(value.map((f) => f.key));

  return (
    <Stack gap="xs">
      {value.map((f, i) => (
        <Group key={f.key} gap="xs" wrap="nowrap">
          <Text size="sm" style={{ flex: 1 }}>
            <DatasetLabel datasetKey={f.key} />
          </Text>
          <ActionIcon
            type="button"
            variant="subtle"
            color="red"
            aria-label={`Remove favorite ${f.title ?? f.key}`}
            onClick={() => onChange(value.filter((_, j) => j !== i))}
          >
            <IconTrash size={16} />
          </ActionIcon>
        </Group>
      ))}
      {value.length === 0 && (
        <Text size="xs" c="dimmed">
          No favorites yet.
        </Text>
      )}
      <TextInput
        aria-label="Search CLB datasets to favorite"
        placeholder="Search CLB datasets to add…"
        value={q}
        onChange={(e) => setQ(e.currentTarget.value)}
      />
      <Stack gap={4}>
        {(hits ?? [])
          .filter((d) => !keys.has(d.key))
          .slice(0, 6)
          .map((d) => (
            <Button
              key={d.key}
              type="button"
              variant="subtle"
              size="xs"
              justify="flex-start"
              onClick={() => {
                onChange([...value, { key: d.key, title: d.title ?? d.alias ?? d.key }]);
                setQ('');
              }}
            >
              + {d.title ?? d.key}
            </Button>
          ))}
      </Stack>
    </Stack>
  );
}
