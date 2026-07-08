import { List, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { getAccepted, getSynonyms } from '../api/usages';

export interface SynonymListProps {
  pid: number;
  usageId: number;
  status: string | null;
}

// Read-only for now (link/unlink synonyms from the UI is a later slice). An accepted usage shows
// its synonyms; a synonym/misapplied usage shows the accepted usage(s) it points to instead.
export default function SynonymList({ pid, usageId, status }: SynonymListProps) {
  const isAccepted = (status ?? '').toUpperCase() === 'ACCEPTED';

  const { data, isLoading } = useQuery({
    queryKey: isAccepted
      ? ['usageSynonyms', pid, usageId]
      : ['usageAccepted', pid, usageId],
    queryFn: () => (isAccepted ? getSynonyms(pid, usageId) : getAccepted(pid, usageId)),
  });

  if (isLoading) return <Text size="sm" c="dimmed">Loading…</Text>;

  const items = data ?? [];
  if (items.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        {isAccepted ? 'No synonyms' : 'No accepted name'}
      </Text>
    );
  }

  return (
    <List size="sm" spacing={4}>
      {items.map((u) => (
        <List.Item key={u.id}>
          {u.scientificName}
          {u.authorship ? (
            <Text span c="dimmed" size="xs">
              {' '}
              {u.authorship}
            </Text>
          ) : null}
        </List.Item>
      ))}
    </List>
  );
}
