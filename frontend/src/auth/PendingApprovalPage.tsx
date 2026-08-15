import { Button, Center, Stack, Text, Textarea, TextInput, Title } from '@mantine/core';
import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { notifications } from '@mantine/notifications';
import { messageFor } from '../api/client';
import { logout, submitApplication } from '../api/auth';
import { useMe } from './useMe';

const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

// Shown by RequireAuth in place of the app chrome when the signed-in user's account is not ACTIVE.
// A brand-new ORCID self-signup lands in PENDING; before an admin can approve them they must
// complete their application by supplying a (required) email and an optional message. DISABLED
// accounts just see a notice. In all cases the API 403s the rest of the app (ActiveUserFilter),
// except /api/me/application which the applicant needs here.
export default function PendingApprovalPage({ state }: { state: string }) {
  const disabled = state === 'DISABLED';
  const { data: me } = useMe();
  const qc = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [email, setEmail] = useState('');
  const [note, setNote] = useState('');

  const hasEmail = !!me?.email;
  const showForm = !disabled && (!hasEmail || editing);

  const apply = useMutation({
    mutationFn: () => submitApplication(email.trim(), note.trim()),
    onSuccess: async () => {
      await qc.invalidateQueries({ queryKey: ['me'] });
      notifications.show({ message: 'Application submitted' });
      setEditing(false);
    },
    onError: (e) =>
      notifications.show({ color: 'red', message: messageFor(e, 'Could not submit application') }),
  });

  const startEdit = () => {
    setEmail(me?.email ?? '');
    setNote('');
    setEditing(true);
  };

  return (
    <Center mih="100vh" p="md">
      <Stack align="center" maw={440} gap="sm">
        {disabled ? (
          <>
            <Title order={3}>Account disabled</Title>
            <Text ta="center" c="dimmed">
              Your account has been disabled by an administrator. Contact an administrator if you
              think this is a mistake.
            </Text>
          </>
        ) : showForm ? (
          <>
            <Title order={3}>Apply for access</Title>
            <Text ta="center" c="dimmed">
              Tell us how to reach you and (optionally) why you'd like access. An administrator will
              review your request.
            </Text>
            <TextInput
              w="100%"
              label="Email"
              required
              value={email}
              error={email.trim() && !EMAIL_RE.test(email.trim()) ? 'Enter a valid email address' : undefined}
              onChange={(e) => setEmail(e.currentTarget.value)}
            />
            <Textarea
              w="100%"
              label="Message"
              description="Optional — a short note for the administrator."
              autosize
              minRows={2}
              value={note}
              onChange={(e) => setNote(e.currentTarget.value)}
            />
            <Button
              onClick={() => apply.mutate()}
              loading={apply.isPending}
              disabled={!EMAIL_RE.test(email.trim())}
            >
              Submit application
            </Button>
          </>
        ) : (
          <>
            <Title order={3}>Awaiting approval</Title>
            <Text ta="center" c="dimmed">
              Your application is registered and awaiting approval by an administrator. You'll be able
              to use Blixa once your account has been approved.
            </Text>
            <Button variant="subtle" onClick={startEdit}>
              Edit application
            </Button>
          </>
        )}
        <Button
          variant="default"
          onClick={async () => {
            await logout();
            window.location.assign('/signin');
          }}
        >
          Sign out
        </Button>
      </Stack>
    </Center>
  );
}
