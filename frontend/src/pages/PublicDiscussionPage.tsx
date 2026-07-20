import { Badge, Container, Group, Paper, Stack, Text, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import {
  getPublicDiscussion,
  listPublicComments,
  type DiscussionStatus,
} from '../api/discussions';
import MentionMarkdown from '../discussions/MentionMarkdown';
import UserAvatar from '../components/UserAvatar';

const STATUS_COLOR: Record<DiscussionStatus, string> = {
  REVIEW: 'yellow',
  OPEN: 'blue',
  REJECTED: 'gray',
  RESOLVED: 'green',
};
const titleCase = (s: string) => s.charAt(0) + s.slice(1).toLowerCase();

// Read-only public view of a PUBLIC discussion (no auth). Name mentions render as plain text since
// there's no public name view to link into. Served under PublicLayout at /p/:pid/discussions/:id.
export default function PublicDiscussionPage() {
  const { pid: pidParam, id } = useParams();
  const pid = Number(pidParam);
  const did = Number(id);

  const { data: discussion, isError } = useQuery({
    queryKey: ['publicDiscussion', pid, did],
    queryFn: () => getPublicDiscussion(pid, did),
    retry: false,
  });
  const { data: comments } = useQuery({
    queryKey: ['publicComments', pid, did],
    queryFn: () => listPublicComments(pid, did),
    retry: false,
    enabled: !isError,
  });

  if (isError) {
    return (
      <Container size="sm" py="xl">
        <Text c="dimmed">This discussion is not available.</Text>
      </Container>
    );
  }
  if (!discussion) return null;

  return (
    <Container size="sm" py="xl">
      <Title order={2}>{discussion.title}</Title>
      <Group gap="xs" my="md">
        <Badge color={STATUS_COLOR[discussion.status]} variant="light">
          {titleCase(discussion.status)}
        </Badge>
        <UserAvatar name={discussion.authorName ?? discussion.authorOrcid} size="sm" />
        <Text size="sm" c="dimmed">
          {discussion.authorName ?? discussion.authorOrcid ?? 'Unknown'} ·{' '}
          {new Date(discussion.createdAt).toLocaleDateString()}
        </Text>
      </Group>

      <Paper withBorder p="md" radius="md" mb="lg">
        {discussion.body ? (
          <MentionMarkdown pid={pid} text={discussion.body} mentions={discussion.mentions} linkUsages={false} />
        ) : (
          <Text c="dimmed" fs="italic">
            No description.
          </Text>
        )}
      </Paper>

      <Title order={4} mb="sm">
        Comments
      </Title>
      <Stack gap="sm">
        {(comments ?? []).map((c) => (
          <Paper key={c.id} withBorder p="sm" radius="md">
            <Group gap="xs" mb={4}>
              <UserAvatar name={c.authorName ?? c.authorOrcid} size="sm" />
              <Text size="sm" fw={600}>
                {c.authorName ?? c.authorOrcid ?? 'Unknown'}
              </Text>
              <Text size="xs" c="dimmed">
                {new Date(c.createdAt).toLocaleDateString()}
              </Text>
            </Group>
            <MentionMarkdown pid={pid} text={c.body} mentions={c.mentions} linkUsages={false} />
          </Paper>
        ))}
        {(comments ?? []).length === 0 && (
          <Text c="dimmed" size="sm">
            No comments.
          </Text>
        )}
      </Stack>
    </Container>
  );
}
