import { Badge, Button, CopyButton, Group, Paper, Stack, Text, Title } from '@mantine/core';
import { modals } from '@mantine/modals';
import { notifications } from '@mantine/notifications';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { listInvitations, resendInvitation, revokeInvitation } from '../api/invitations';
import { messageFor } from '../api/client';

dayjs.extend(relativeTime);

// Owner-only list of a project's not-yet-accepted email invitations. Copy link is the fallback
// when outgoing mail isn't configured; Resend issues a new link (the old one stops working).
export default function PendingInvitations({ projectId }: { projectId: number }) {
  const queryClient = useQueryClient();
  const { data: invitations } = useQuery({
    queryKey: ['invitations', projectId],
    queryFn: () => listInvitations(projectId),
  });
  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['invitations', projectId] });

  const resendMut = useMutation({
    mutationFn: (id: number) => resendInvitation(projectId, id),
    onSuccess: (inv) => {
      invalidate();
      notifications.show({ color: 'green', message: `Invitation re-sent to ${inv.email}` });
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not resend the invitation') }),
  });
  const revokeMut = useMutation({
    mutationFn: (id: number) => revokeInvitation(projectId, id),
    onSuccess: invalidate,
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not revoke the invitation') }),
  });

  if (!invitations || invitations.length === 0) return null;

  return (
    <Stack gap="xs" mt="xl">
      <Title order={4}>Pending invitations</Title>
      {invitations.map((inv) => (
        <Paper key={inv.id} withBorder p="sm">
          <Group justify="space-between" align="flex-start" wrap="nowrap">
            <Stack gap={2}>
              <Group gap="xs">
                <Text size="sm" fw={500}>
                  {inv.email}
                </Text>
                <Badge size="sm" variant="light">
                  {inv.role}
                </Badge>
                {inv.expired && (
                  <Badge size="sm" variant="light" color="red">
                    expired
                  </Badge>
                )}
              </Group>
              {inv.message && (
                <Text size="sm" c="dimmed">
                  {inv.message}
                </Text>
              )}
              <Text size="xs" c="dimmed">
                invited {inv.invitedBy ? `by ${inv.invitedBy} ` : ''}
                {dayjs(inv.createdAt).fromNow()}
              </Text>
            </Stack>
            <Group gap="xs" wrap="nowrap">
              <CopyButton value={inv.acceptUrl}>
                {({ copied, copy }) => (
                  <Button size="xs" variant="subtle" onClick={copy}>
                    {copied ? 'Copied' : 'Copy link'}
                  </Button>
                )}
              </CopyButton>
              <Button
                size="xs"
                variant="subtle"
                loading={resendMut.isPending && resendMut.variables === inv.id}
                onClick={() => resendMut.mutate(inv.id)}
              >
                Resend
              </Button>
              <Button
                size="xs"
                variant="subtle"
                color="red"
                onClick={() =>
                  modals.openConfirmModal({
                    title: 'Revoke invitation?',
                    children: <Text size="sm">The link sent to {inv.email} will stop working.</Text>,
                    labels: { confirm: 'Revoke', cancel: 'Cancel' },
                    confirmProps: { color: 'red' },
                    onConfirm: () => revokeMut.mutate(inv.id),
                  })
                }
              >
                Revoke
              </Button>
            </Group>
          </Group>
        </Paper>
      ))}
    </Stack>
  );
}
