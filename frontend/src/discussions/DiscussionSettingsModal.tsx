import { Button, Code, CopyButton, Group, Modal, Stack, Text, TextInput } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { notifications } from '@mantine/notifications';
import { messageFor } from '../api/client';
import {
  generateDiscussionToken,
  getDiscussionToken,
  revokeDiscussionToken,
} from '../api/discussions';

// Editor-only: manage the per-project API token that lets an external system (e.g. COL feedback)
// submit discussions. Submissions arrive as REVIEW for editors to accept.
export default function DiscussionSettingsModal({
  pid,
  opened,
  onClose,
}: {
  pid: number;
  opened: boolean;
  onClose: () => void;
}) {
  const qc = useQueryClient();
  const { data } = useQuery({
    queryKey: ['discussionToken', pid],
    queryFn: () => getDiscussionToken(pid),
    enabled: opened,
  });
  const token = data?.token ?? null;
  const invalidate = () => qc.invalidateQueries({ queryKey: ['discussionToken', pid] });

  const gen = useMutation({
    mutationFn: () => generateDiscussionToken(pid),
    onSuccess: async () => {
      await invalidate();
      notifications.show({ message: 'Token generated' });
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not generate') }),
  });
  const revoke = useMutation({
    mutationFn: () => revokeDiscussionToken(pid),
    onSuccess: async () => {
      await invalidate();
      notifications.show({ message: 'Token revoked' });
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not revoke') }),
  });

  const endpoint = `${window.location.origin}/api/public/projects/${pid}/discussions`;

  return (
    <Modal opened={opened} onClose={onClose} title="External submissions" size="lg">
      <Stack>
        <Text size="sm" c="dimmed">
          Give this token to an external system (e.g. the COL feedback bot) to submit discussions via
          the API. Submissions arrive with status <b>Review</b> for an editor to accept or reject.
        </Text>

        {token ? (
          <Group align="flex-end" gap="xs">
            <TextInput label="API token" readOnly value={token} style={{ flex: 1 }} />
            <CopyButton value={token}>
              {({ copied, copy }) => (
                <Button variant="default" onClick={copy}>
                  {copied ? 'Copied' : 'Copy'}
                </Button>
              )}
            </CopyButton>
          </Group>
        ) : (
          <Text size="sm">No token yet — generate one to enable external submissions.</Text>
        )}

        <Group>
          <Button onClick={() => gen.mutate()} loading={gen.isPending}>
            {token ? 'Regenerate' : 'Generate token'}
          </Button>
          {token && (
            <Button variant="subtle" color="red" onClick={() => revoke.mutate()} loading={revoke.isPending}>
              Revoke
            </Button>
          )}
        </Group>

        <div>
          <Text size="sm" fw={600} mb={4}>
            Endpoint
          </Text>
          <Code block>
            {`POST ${endpoint}\nX-Api-Token: <token>\nContent-Type: application/json\n\n{"title": "…", "body": "…", "authorOrcid": "0000-…"}`}
          </Code>
        </div>
      </Stack>
    </Modal>
  );
}
