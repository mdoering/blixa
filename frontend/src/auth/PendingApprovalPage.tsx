import { Button, Center, Stack, Text, Title } from '@mantine/core';
import { logout } from '../api/auth';

// Shown by RequireAuth in place of the app chrome when the signed-in user's account is not ACTIVE.
// A brand-new ORCID self-signup lands in PENDING (see AppUserService.upsertFromOrcid) until an
// admin approves it; an admin can also DISABLE an account. In both cases the API 403s every
// protected route (ActiveUserFilter), so there is nothing useful to show but this gate.
export default function PendingApprovalPage({ state }: { state: string }) {
  const disabled = state === 'DISABLED';
  return (
    <Center mih="100vh" p="md">
      <Stack align="center" maw={440} gap="sm">
        <Title order={3}>{disabled ? 'Account disabled' : 'Awaiting approval'}</Title>
        <Text ta="center" c="dimmed">
          {disabled
            ? 'Your account has been disabled by an administrator. Contact an administrator if you think this is a mistake.'
            : 'Your account is registered and awaiting approval by an administrator. You will be able to use Blixa once your account has been approved.'}
        </Text>
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
