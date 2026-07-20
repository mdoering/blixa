import { Button, Group, Modal, Select, Stack, Textarea, TextInput } from '@mantine/core';
import { modals } from '@mantine/modals';
import { notifications } from '@mantine/notifications';
import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { messageFor } from '../api/client';
import {
  createDiscussion,
  deleteDiscussion,
  setDiscussionStatus,
  updateDiscussion,
  type Discussion,
  type DiscussionStatus,
} from '../api/discussions';

const STATUSES: DiscussionStatus[] = ['REVIEW', 'OPEN', 'REJECTED', 'RESOLVED'];
const titleCase = (s: string) => s.charAt(0) + s.slice(1).toLowerCase();

interface Props {
  pid: number;
  discussion: Discussion | null; // null = create
  opened: boolean;
  canManage: boolean;
  onClose: () => void;
}

// Create / view / edit a discussion. On an existing discussion, an editor or the author may edit the
// title/body, change the status, or delete it; everyone else sees it read-only (the backend enforces
// this too). Status changes go through the dedicated /status endpoint when they differ from the
// loaded value, so a plain edit doesn't need to resend the status.
export default function DiscussionForm({ pid, discussion, opened, canManage, onClose }: Props) {
  const qc = useQueryClient();
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [status, setStatus] = useState<DiscussionStatus>('OPEN');

  useEffect(() => {
    setTitle(discussion?.title ?? '');
    setBody(discussion?.body ?? '');
    setStatus(discussion?.status ?? 'OPEN');
  }, [discussion, opened]);

  const invalidate = () => qc.invalidateQueries({ queryKey: ['discussions', pid] });

  const save = useMutation({
    mutationFn: async () => {
      if (discussion) {
        await updateDiscussion(pid, discussion.id, {
          title,
          body: body || null,
          version: discussion.version,
        });
        if (status !== discussion.status) await setDiscussionStatus(pid, discussion.id, status);
      } else {
        await createDiscussion(pid, { title, body: body || null });
      }
    },
    onSuccess: async () => {
      await invalidate();
      notifications.show({ message: discussion ? 'Saved' : 'Discussion created' });
      onClose();
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not save') }),
  });

  const del = useMutation({
    mutationFn: () => deleteDiscussion(pid, discussion!.id),
    onSuccess: async () => {
      await invalidate();
      notifications.show({ message: 'Deleted' });
      onClose();
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not delete') }),
  });

  const confirmDelete = () =>
    modals.openConfirmModal({
      title: 'Delete this discussion?',
      children: `This permanently deletes "${discussion?.title}".`,
      labels: { confirm: 'Delete', cancel: 'Cancel' },
      confirmProps: { color: 'red' },
      onConfirm: () => del.mutate(),
    });

  const isEdit = discussion !== null;
  const readOnly = isEdit && !canManage;

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={isEdit ? `Discussion #${discussion?.id}` : 'New discussion'}
      size="lg"
    >
      <Stack>
        <TextInput
          label="Title"
          required
          value={title}
          onChange={(e) => setTitle(e.currentTarget.value)}
          readOnly={readOnly}
        />
        <Textarea
          label="Body"
          description="Markdown supported"
          autosize
          minRows={4}
          value={body}
          onChange={(e) => setBody(e.currentTarget.value)}
          readOnly={readOnly}
        />
        {isEdit && (
          <Select
            label="Status"
            data={STATUSES.map((s) => ({ value: s, label: titleCase(s) }))}
            value={status}
            onChange={(v) => v && setStatus(v as DiscussionStatus)}
            disabled={readOnly}
            allowDeselect={false}
          />
        )}
        <Group justify="space-between">
          {isEdit && canManage ? (
            <Button variant="subtle" color="red" onClick={confirmDelete}>
              Delete
            </Button>
          ) : (
            <span />
          )}
          <Group>
            <Button variant="default" onClick={onClose}>
              Cancel
            </Button>
            {!readOnly && (
              <Button onClick={() => save.mutate()} loading={save.isPending} disabled={!title.trim()}>
                {isEdit ? 'Save' : 'Create'}
              </Button>
            )}
          </Group>
        </Group>
      </Stack>
    </Modal>
  );
}
