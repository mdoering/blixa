import { Button, Group, Modal, Stack, Text, TextInput } from '@mantine/core';
import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { notifications } from '@mantine/notifications';
import { messageFor } from '../api/client';
import { updateEmail, updateUsername } from '../api/auth';
import { useMe } from '../auth/useMe';

const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

// Account settings: pick a custom, unique username (the display handle used for @mentions) and set a
// contact email (used for lifecycle notifications). ORCID stays as a read-only reference.
export default function AccountModal({ opened, onClose }: { opened: boolean; onClose: () => void }) {
  const { data: me } = useMe();
  const qc = useQueryClient();
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');

  useEffect(() => {
    if (opened) {
      setUsername(me?.username ?? '');
      setEmail(me?.email ?? '');
    }
  }, [opened, me]);

  const save = useMutation({
    mutationFn: async () => {
      if (username.trim() !== (me?.username ?? '')) await updateUsername(username.trim());
      return updateEmail(email.trim());
    },
    onSuccess: async () => {
      await qc.invalidateQueries({ queryKey: ['me'] });
      notifications.show({ message: 'Account updated' });
      onClose();
    },
    onError: (e) =>
      notifications.show({ color: 'red', message: messageFor(e, 'Could not update account') }),
  });

  const emailValid = EMAIL_RE.test(email.trim());

  return (
    <Modal opened={opened} onClose={onClose} title="Account" size="md">
      <Stack>
        <TextInput
          label="Username"
          description="Your unique handle — letters, digits, _ or - (min 2). Used for @mentions."
          value={username}
          onChange={(e) => setUsername(e.currentTarget.value)}
        />
        <TextInput
          label="Email"
          description="Where we send account notifications (e.g. when your application is approved)."
          value={email}
          error={email.trim() && !emailValid ? 'Enter a valid email address' : undefined}
          onChange={(e) => setEmail(e.currentTarget.value)}
        />
        {me?.orcid ? (
          <Text size="xs" c="dimmed">
            ORCID: {me.orcid}
          </Text>
        ) : null}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            Cancel
          </Button>
          <Button
            onClick={() => save.mutate()}
            loading={save.isPending}
            disabled={!username.trim() || !emailValid}
          >
            Save
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
