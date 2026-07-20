import { Anchor, Badge, Box, Button, Group, Paper, Stack, Text, Textarea, Title } from '@mantine/core';
import { IconArrowLeft, IconPencil } from '@tabler/icons-react';
import { useState } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, Navigate, useParams } from 'react-router-dom';
import { notifications } from '@mantine/notifications';
import { messageFor } from '../api/client';
import { getProject } from '../api/projects';
import { useMe } from '../auth/useMe';
import {
  createComment,
  getDiscussion,
  listComments,
  type Comment,
  type DiscussionStatus,
} from '../api/discussions';
import UserAvatar from '../components/UserAvatar';
import MentionMarkdown from './MentionMarkdown';
import CommentItem from './CommentItem';
import DiscussionForm from './DiscussionForm';

const STATUS_COLOR: Record<DiscussionStatus, string> = {
  REVIEW: 'yellow',
  OPEN: 'blue',
  REJECTED: 'gray',
  RESOLVED: 'green',
};
const titleCase = (s: string) => s.charAt(0) + s.slice(1).toLowerCase();

// A single discussion thread: rendered markdown body (with #nameID / @orcid mention links), status +
// author, editor/author controls, and the comment thread with a composer.
export default function DiscussionDetailPage() {
  const { projectId, id } = useParams();
  const pid = Number(projectId);
  const did = Number(id);
  const qc = useQueryClient();
  const { data: me } = useMe();
  const { data: project } = useQuery({ queryKey: ['project', pid], queryFn: () => getProject(pid) });
  const isEditor = project ? ['owner', 'editor'].includes(project.role) : false;

  const { data: discussion, isError } = useQuery({
    queryKey: ['discussion', pid, did],
    queryFn: () => getDiscussion(pid, did),
    retry: false,
  });
  const { data: comments } = useQuery({
    queryKey: ['comments', pid, did],
    queryFn: () => listComments(pid, did),
    placeholderData: keepPreviousData,
  });

  const [editOpen, setEditOpen] = useState(false);
  const [newComment, setNewComment] = useState('');

  const post = useMutation({
    mutationFn: () => createComment(pid, did, newComment),
    onSuccess: async () => {
      setNewComment('');
      await qc.invalidateQueries({ queryKey: ['comments', pid, did] });
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not comment') }),
  });

  // deleted (or not found) -> back to the list
  if (isError) return <Navigate to={`/projects/${pid}/discussions`} replace />;
  if (!discussion) return null;

  const canManageDiscussion = isEditor || (me != null && discussion.authorId === me.id);
  const canManageComment = (c: Comment) => isEditor || (me != null && c.authorId === me.id);

  return (
    <Box maw={800}>
      <Anchor component={Link} to={`/projects/${pid}/discussions`} size="sm">
        <Group gap={4}>
          <IconArrowLeft size={14} />
          Back to discussions
        </Group>
      </Anchor>

      <Group justify="space-between" mt="sm" mb="xs" align="flex-start">
        <Title order={3} m={0}>
          {discussion.title}
        </Title>
        <Group gap="xs">
          {discussion.visibility === 'PUBLIC' && (
            <Anchor
              href={`/p/${pid}/discussions/${did}`}
              target="_blank"
              rel="noopener noreferrer"
              size="sm"
            >
              View public page ↗
            </Anchor>
          )}
          {canManageDiscussion && (
            <Button
              variant="default"
              size="xs"
              leftSection={<IconPencil size={14} />}
              onClick={() => setEditOpen(true)}
            >
              Edit
            </Button>
          )}
        </Group>
      </Group>

      <Group gap="xs" mb="md">
        <Badge color={STATUS_COLOR[discussion.status]} variant="light">
          {titleCase(discussion.status)}
        </Badge>
        {discussion.visibility === 'PUBLIC' && (
          <Badge color="teal" variant="outline">
            Public
          </Badge>
        )}
        <UserAvatar name={discussion.authorName ?? discussion.authorOrcid} size="sm" />
        <Text size="sm" c="dimmed">
          #{discussion.id} · {discussion.authorName ?? discussion.authorOrcid ?? 'Unknown'} ·{' '}
          {new Date(discussion.createdAt).toLocaleString()}
        </Text>
      </Group>

      <Paper withBorder p="md" radius="md" mb="lg">
        {discussion.body ? (
          <MentionMarkdown pid={pid} text={discussion.body} mentions={discussion.mentions} />
        ) : (
          <Text c="dimmed" fs="italic">
            No description.
          </Text>
        )}
      </Paper>

      <Title order={5} mb="sm">
        Comments
      </Title>
      <Stack gap="sm" mb="md">
        {(comments ?? []).map((c) => (
          <CommentItem key={c.id} pid={pid} comment={c} canManage={canManageComment(c)} />
        ))}
        {(comments ?? []).length === 0 && (
          <Text c="dimmed" size="sm">
            No comments yet.
          </Text>
        )}
      </Stack>

      <Paper withBorder p="sm" radius="md">
        <Textarea
          aria-label="Add a comment"
          placeholder="Add a comment…  (markdown; #nameID and @orcid supported)"
          autosize
          minRows={2}
          value={newComment}
          onChange={(e) => setNewComment(e.currentTarget.value)}
        />
        <Group justify="flex-end" mt="xs">
          <Button size="xs" onClick={() => post.mutate()} loading={post.isPending} disabled={!newComment.trim()}>
            Comment
          </Button>
        </Group>
      </Paper>

      {editOpen && (
        <DiscussionForm
          pid={pid}
          discussion={discussion}
          opened
          canManage={canManageDiscussion}
          isEditor={isEditor}
          onClose={() => setEditOpen(false)}
        />
      )}
    </Box>
  );
}
