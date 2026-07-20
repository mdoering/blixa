import { ActionIcon, Box, Button, Group, Menu, Paper, Stack, Text } from '@mantine/core';
import { IconDots, IconPencil, IconTrash } from '@tabler/icons-react';
import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { modals } from '@mantine/modals';
import { notifications } from '@mantine/notifications';
import { messageFor } from '../api/client';
import { deleteComment, updateComment, type Comment } from '../api/discussions';
import UserAvatar from '../components/UserAvatar';
import MentionMarkdown from './MentionMarkdown';
import MentionTextarea from './MentionTextarea';

interface Props {
  pid: number;
  comment: Comment;
  canManage: boolean;
}

export default function CommentItem({ pid, comment, canManage }: Props) {
  const qc = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [body, setBody] = useState(comment.body);

  const invalidate = () =>
    qc.invalidateQueries({ queryKey: ['comments', pid, comment.discussionId] });

  const save = useMutation({
    mutationFn: () =>
      updateComment(pid, comment.discussionId, comment.id, { body, version: comment.version }),
    onSuccess: async () => {
      await invalidate();
      setEditing(false);
      notifications.show({ message: 'Saved' });
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not save') }),
  });

  const del = useMutation({
    mutationFn: () => deleteComment(pid, comment.discussionId, comment.id),
    onSuccess: async () => {
      await invalidate();
      notifications.show({ message: 'Deleted' });
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not delete') }),
  });

  const confirmDelete = () =>
    modals.openConfirmModal({
      title: 'Delete this comment?',
      labels: { confirm: 'Delete', cancel: 'Cancel' },
      confirmProps: { color: 'red' },
      onConfirm: () => del.mutate(),
    });

  return (
    <Paper withBorder p="sm" radius="md">
      <Group justify="space-between" mb={4}>
        <Group gap="xs">
          <UserAvatar name={comment.authorName ?? comment.authorOrcid} size="sm" />
          <Text size="sm" fw={600}>
            {comment.authorName ?? comment.authorOrcid ?? 'Unknown'}
          </Text>
        </Group>
        <Group gap="xs">
          <Text size="xs" c="dimmed">
            {new Date(comment.createdAt).toLocaleString()}
          </Text>
          {canManage && !editing && (
            <Menu withinPortal position="bottom-end">
              <Menu.Target>
                <ActionIcon variant="subtle" color="gray" aria-label="Comment actions">
                  <IconDots size={16} />
                </ActionIcon>
              </Menu.Target>
              <Menu.Dropdown>
                <Menu.Item
                  leftSection={<IconPencil size={14} />}
                  onClick={() => {
                    setBody(comment.body);
                    setEditing(true);
                  }}
                >
                  Edit
                </Menu.Item>
                <Menu.Item
                  color="red"
                  leftSection={<IconTrash size={14} />}
                  onClick={confirmDelete}
                >
                  Delete
                </Menu.Item>
              </Menu.Dropdown>
            </Menu>
          )}
        </Group>
      </Group>
      {editing ? (
        <Stack gap="xs">
          <MentionTextarea pid={pid} autosize minRows={2} value={body} onChange={setBody} />
          <Group justify="flex-end" gap="xs">
            <Button variant="default" size="xs" onClick={() => setEditing(false)}>
              Cancel
            </Button>
            <Button size="xs" onClick={() => save.mutate()} loading={save.isPending} disabled={!body.trim()}>
              Save
            </Button>
          </Group>
        </Stack>
      ) : (
        <Box>
          <MentionMarkdown pid={pid} text={comment.body} mentions={comment.mentions} />
        </Box>
      )}
    </Paper>
  );
}
