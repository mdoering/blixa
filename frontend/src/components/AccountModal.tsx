import { Button, Group, Modal, Stack, Text, TextInput } from '@mantine/core';
import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { notifications } from '@mantine/notifications';
import { messageFor } from '../api/client';
import { updateUsername } from '../api/auth';
import { useMe } from '../auth/useMe';

// Account settings: pick a custom, unique username (the display handle used for @mentions). ORCID
// stays as a read-only reference. There's no profile photo (ORCID exposes none) -- avatars are
// initials-based (see UserAvatar).
export default function AccountModal({ opened, onClose }: { opened: boolean; onClose: () => void }) {
  const { data: me } = useMe();
  const qc = useQueryClient();
  const [username, setUsername] = useState('');

  useEffect(() => {
    if (opened) setUsername(me?.username ?? '');
  }, [opened, me]);

  const save = useMutation({
    mutationFn: () => updateUsername(username.trim()),
    onSuccess: async () => {
      await qc.invalidateQueries({ queryKey: ['me'] });
      notifications.show({ message: 'Username updated' });
      onClose();
    },
    onError: (e) =>
      notifications.show({ color: 'red', message: messageFor(e, 'Could not update username') }),
  });

  return (
    <Modal opened={opened} onClose={onClose} title="Account" size="md">
      <Stack>
        <TextInput
          label="Username"
          description="Your unique handle — letters, digits, _ or - (min 2). Used for @mentions."
          value={username}
          onChange={(e) => setUsername(e.currentTarget.value)}
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
          <Button onClick={() => save.mutate()} loading={save.isPending} disabled={!username.trim()}>
            Save
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
