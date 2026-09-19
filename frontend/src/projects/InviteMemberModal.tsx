import { Button, Group, Modal, Select, Stack, Textarea, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { notifications } from '@mantine/notifications';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { createInvitation } from '../api/invitations';
import { messageFor } from '../api/client';
import type { Role } from '../api/types';
import { ROLE_DATA } from './roles';

interface Values {
  email: string;
  role: Role;
  message: string;
}

// Owner invites someone who may not be in Blixa yet: the backend emails them a link (owner on CC).
export default function InviteMemberModal({
  projectId,
  opened,
  onClose,
}: {
  projectId: number;
  opened: boolean;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const form = useForm<Values>({
    initialValues: { email: '', role: 'editor', message: '' },
    validate: {
      email: (v) => (/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(v.trim()) ? null : 'Enter a valid email address'),
    },
  });

  const close = () => {
    form.reset();
    onClose();
  };

  const mut = useMutation({
    mutationFn: (v: Values) =>
      createInvitation(projectId, {
        email: v.email.trim(),
        role: v.role,
        ...(v.message.trim() ? { message: v.message.trim() } : {}),
      }),
    onSuccess: (inv) => {
      queryClient.invalidateQueries({ queryKey: ['invitations', projectId] });
      notifications.show({ color: 'green', message: `Invitation sent to ${inv.email}` });
      close();
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not send the invitation') }),
  });

  return (
    <Modal opened={opened} onClose={close} title="Invite by email">
      <form onSubmit={form.onSubmit((v) => mut.mutate(v))}>
        <Stack>
          <TextInput label="Email" withAsterisk {...form.getInputProps('email')} />
          <Select label="Role" data={ROLE_DATA} allowDeselect={false} {...form.getInputProps('role')} />
          <Textarea
            label="Message"
            description="Optional — included in the invitation email"
            autosize
            minRows={3}
            {...form.getInputProps('message')}
          />
          <Group justify="flex-end">
            <Button variant="default" onClick={close}>
              Cancel
            </Button>
            <Button type="submit" loading={mut.isPending}>
              Send invitation
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
