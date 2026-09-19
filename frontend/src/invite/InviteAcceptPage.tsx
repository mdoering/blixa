import { useEffect } from 'react';
import { Alert, Anchor, Blockquote, Button, Card, Center, Loader, Stack, Text, Title } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router-dom';
import BlixaLogo from '../components/BlixaLogo';
import { acceptInvitation, getInvitationPreview } from '../api/invitations';
import { orcidLoginUrl } from '../api/auth';
import { useConfig } from '../api/config';
import { messageFor } from '../api/client';
import { useMe } from '../auth/useMe';
import { roleWithArticle } from '../projects/roles';
import { clearPendingInvite, savePendingInvite } from './pendingInvite';

// Landing page of an emailed project invitation. Public: shows what the link is for, then either
// sends a signed-out visitor to sign in (remembering the token for RequireAuth to resume) or lets a
// signed-in user -- including a brand-new PENDING account -- accept and jump into the project.
export default function InviteAcceptPage() {
  const { token = '' } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { data: config, isLoading: configLoading } = useConfig();
  const { data: me, isLoading: meLoading } = useMe();
  const preview = useQuery({
    queryKey: ['invitationPreview', token],
    queryFn: () => getInvitationPreview(token),
  });
  const valid = preview.data?.status === 'VALID';

  useEffect(() => {
    if (preview.isError || (preview.data && !valid)) {
      clearPendingInvite(); // dead link: nothing to carry across sign-in
    } else if (valid && !meLoading) {
      // Signed in: the token is in the URL now, so drop the stored copy -- otherwise RequireAuth
      // would keep bouncing the user back here if they leave without accepting.
      if (me) clearPendingInvite();
      else savePendingInvite(token);
    }
  }, [preview.isError, preview.data, valid, meLoading, me, token]);

  const accept = useMutation({
    mutationFn: () => acceptInvitation(token),
    onSuccess: async ({ projectId }) => {
      clearPendingInvite();
      await queryClient.invalidateQueries({ queryKey: ['me'] });
      await queryClient.invalidateQueries({ queryKey: ['projects'] });
      navigate(`/projects/${projectId}`, { replace: true });
    },
  });

  let body;
  // configLoading too: otherwise a signed-out visitor briefly sees the local "Sign in" button
  // before the ORCID one.
  if (preview.isLoading || meLoading || configLoading) {
    body = (
      <Center py="md">
        <Loader />
      </Center>
    );
  } else if (preview.isError || !preview.data) {
    body = <Alert color="red">This invitation link is not valid.</Alert>;
  } else if (preview.data.status === 'EXPIRED') {
    body = (
      <Alert color="yellow">
        This invitation has expired. Ask the project owner to send you a new one.
      </Alert>
    );
  } else if (preview.data.status === 'ACCEPTED') {
    body = (
      <Alert color="blue">
        This invitation has already been used.{' '}
        <Anchor component={Link} to="/projects">
          Go to your projects
        </Anchor>
      </Alert>
    );
  } else {
    const p = preview.data;
    body = (
      <Stack gap="md">
        <Title order={3}>Join “{p.projectTitle}”</Title>
        <Text>
          {p.invitedBy ?? 'A project owner'} invited you to join as {roleWithArticle(p.role)}.
        </Text>
        {p.message && <Blockquote p="sm">{p.message}</Blockquote>}
        {me ? (
          <>
            {accept.isError && (
              <Alert color="red">{messageFor(accept.error, 'Could not accept the invitation')}</Alert>
            )}
            <Button fullWidth loading={accept.isPending} onClick={() => accept.mutate()}>
              Accept invitation
            </Button>
          </>
        ) : config?.orcidEnabled ? (
          <Button fullWidth variant="default" component="a" href={orcidLoginUrl()}>
            Sign in with ORCID to accept
          </Button>
        ) : (
          <Button fullWidth variant="default" component={Link} to="/signin">
            Sign in to accept
          </Button>
        )}
      </Stack>
    );
  }

  return (
    <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 80 }}>
      <Card withBorder style={{ width: 440, maxWidth: 'calc(100vw - 32px)' }}>
        <BlixaLogo variant="text" height={32} style={{ display: 'block', margin: '4px auto 20px' }} />
        {body}
      </Card>
    </div>
  );
}
