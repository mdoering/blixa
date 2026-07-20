import { Anchor, Badge, Group, Stack, Text } from '@mantine/core';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { listUsageDiscussions, type DiscussionStatus } from '../api/discussions';

const STATUS_COLOR: Record<DiscussionStatus, string> = {
  REVIEW: 'yellow',
  OPEN: 'blue',
  REJECTED: 'gray',
  RESOLVED: 'green',
};
const titleCase = (s: string) => s.charAt(0) + s.slice(1).toLowerCase();

// Discussions that mention this name (via #nameID in their body or a comment). Shown as a tab on
// TaxonDetail so a curator can jump from a name to the conversations about it.
export default function UsageDiscussionsTab({ pid, usageId }: { pid: number; usageId: number }) {
  const { data } = useQuery({
    queryKey: ['usageDiscussions', pid, usageId],
    queryFn: () => listUsageDiscussions(pid, usageId),
  });
  const rows = data ?? [];

  if (rows.length === 0) {
    return (
      <Text c="dimmed" size="sm">
        No discussions mention this name yet.
      </Text>
    );
  }

  return (
    <Stack gap="xs">
      {rows.map((d) => (
        <Group key={d.id} gap="xs" wrap="nowrap">
          <Badge color={STATUS_COLOR[d.status]} variant="light" size="sm">
            {titleCase(d.status)}
          </Badge>
          <Anchor component={Link} to={`/projects/${pid}/discussions/${d.id}`}>
            {d.title}
          </Anchor>
        </Group>
      ))}
    </Stack>
  );
}
