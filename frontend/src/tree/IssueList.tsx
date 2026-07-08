import { Badge, Group, Stack, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { getEntityIssues } from '../api/issues';

export interface IssueListProps {
  pid: number;
  entityId: number;
}

const SEVERITY_COLOR: Record<string, string> = {
  info: 'blue',
  warning: 'yellow',
  error: 'red',
};

export default function IssueList({ pid, entityId }: IssueListProps) {
  const { data, isLoading } = useQuery({
    queryKey: ['usageIssues', pid, entityId],
    queryFn: () => getEntityIssues(pid, 'name_usage', entityId),
  });

  if (isLoading) return <Text size="sm" c="dimmed">Loading…</Text>;

  const issues = data ?? [];
  if (issues.length === 0) return <Text size="sm" c="dimmed">No issues</Text>;

  return (
    <Stack gap={6}>
      {issues.map((issue) => (
        <Group key={issue.id} gap={8} wrap="nowrap" align="flex-start">
          <Badge color={SEVERITY_COLOR[issue.severity.toLowerCase()] ?? 'gray'} size="sm">
            {issue.severity}
          </Badge>
          <Text size="sm">{issue.message}</Text>
        </Group>
      ))}
    </Stack>
  );
}
